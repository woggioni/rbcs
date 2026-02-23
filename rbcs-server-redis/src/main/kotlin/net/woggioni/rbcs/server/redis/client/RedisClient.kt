package net.woggioni.rbcs.server.redis.client

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFactory
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOption
import io.netty.channel.ChannelPipeline
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.pool.AbstractChannelPoolHandler
import io.netty.channel.pool.FixedChannelPool
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.redis.ArrayRedisMessage
import io.netty.handler.codec.redis.ErrorRedisMessage
import io.netty.handler.codec.redis.FullBulkStringRedisMessage
import io.netty.handler.codec.redis.RedisArrayAggregator
import io.netty.handler.codec.redis.RedisBulkStringAggregator
import io.netty.handler.codec.redis.RedisDecoder
import io.netty.handler.codec.redis.RedisEncoder
import io.netty.handler.codec.redis.RedisMessage
import io.netty.util.concurrent.Future as NettyFuture
import io.netty.util.concurrent.GenericFutureListener

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

import net.woggioni.rbcs.common.HostAndPort
import net.woggioni.rbcs.common.createLogger
import net.woggioni.rbcs.common.trace
import net.woggioni.rbcs.server.redis.RedisCacheConfiguration
import net.woggioni.rbcs.server.redis.RedisCacheHandler


class RedisClient(
    private val servers: List<RedisCacheConfiguration.Server>,
    private val chunkSize: Int,
    private val group: EventLoopGroup,
    private val channelFactory: ChannelFactory<SocketChannel>,
    private val connectionPool: ConcurrentHashMap<HostAndPort, FixedChannelPool>,
) : AutoCloseable {

    private companion object {
        private val log = createLogger<RedisCacheHandler>()
    }

    private fun newConnectionPool(server: RedisCacheConfiguration.Server): FixedChannelPool {
        val bootstrap = Bootstrap().apply {
            group(group)
            channelFactory(channelFactory)
            option(ChannelOption.SO_KEEPALIVE, true)
            remoteAddress(InetSocketAddress(server.endpoint.host, server.endpoint.port))
            server.connectionTimeoutMillis?.let {
                option(ChannelOption.CONNECT_TIMEOUT_MILLIS, it)
            }
        }
        val channelPoolHandler = object : AbstractChannelPoolHandler() {

            override fun channelCreated(ch: Channel) {
                val pipeline: ChannelPipeline = ch.pipeline()
                pipeline.addLast(RedisEncoder())
                pipeline.addLast(RedisDecoder())
                pipeline.addLast(RedisBulkStringAggregator())
                pipeline.addLast(RedisArrayAggregator())
                server.password?.let { password ->
                    // Send AUTH command synchronously on new connections
                    val authCmd = buildCommand("AUTH", password)
                    ch.writeAndFlush(authCmd).addListener(ChannelFutureListener { future ->
                        if (!future.isSuccess) {
                            ch.close()
                        }
                    })
                    // Install a one-shot handler to consume the AUTH response
                    pipeline.addLast(object : SimpleChannelInboundHandler<RedisMessage>() {
                        override fun channelRead0(ctx: ChannelHandlerContext, msg: RedisMessage) {
                            when (msg) {
                                is ErrorRedisMessage -> {
                                    ctx.close()
                                }
                                else -> {
                                    // AUTH succeeded, remove this one-shot handler
                                    ctx.pipeline().remove(this)
                                }
                            }
                        }
                    })
                }
            }
        }
        return FixedChannelPool(bootstrap, channelPoolHandler, server.maxConnections)
    }

    private fun buildCommand(vararg args: String): ArrayRedisMessage {
        val children = args.map { arg ->
            FullBulkStringRedisMessage(
                Unpooled.wrappedBuffer(arg.toByteArray(StandardCharsets.UTF_8))
            )
        }
        return ArrayRedisMessage(children)
    }

    fun sendCommand(
        key: ByteArray,
        alloc: ByteBufAllocator,
        responseHandler: RedisResponseHandler,
    ): CompletableFuture<Channel> {
        val server = if (servers.size > 1) {
            val keyBuffer = alloc.buffer(key.size)
            keyBuffer.writeBytes(key)
            var checksum = 0
            while (keyBuffer.readableBytes() > 4) {
                val byte = keyBuffer.readInt()
                checksum = checksum xor byte
            }
            while (keyBuffer.readableBytes() > 0) {
                val byte = keyBuffer.readByte()
                checksum = checksum xor byte.toInt()
            }
            keyBuffer.release()
            servers[Math.floorMod(checksum, servers.size)]
        } else {
            servers.first()
        }

        val response = CompletableFuture<Channel>()
        val pool = connectionPool.computeIfAbsent(server.endpoint) {
            newConnectionPool(server)
        }
        pool.acquire().addListener(object : GenericFutureListener<NettyFuture<Channel>> {
            override fun operationComplete(channelFuture: NettyFuture<Channel>) {
                if (channelFuture.isSuccess) {
                    val channel = channelFuture.now
                    var connectionClosedByTheRemoteServer = true
                    val closeCallback = {
                        if (connectionClosedByTheRemoteServer) {
                            val ex = IOException("The Redis server closed the connection")
                            val completed = response.completeExceptionally(ex)
                            if (!completed) responseHandler.exceptionCaught(ex)
                        }
                    }
                    val closeListener = ChannelFutureListener {
                        closeCallback()
                    }
                    channel.closeFuture().addListener(closeListener)
                    val pipeline = channel.pipeline()
                    val handler = object : SimpleChannelInboundHandler<RedisMessage>(false) {

                        override fun handlerAdded(ctx: ChannelHandlerContext) {
                            channel.closeFuture().removeListener(closeListener)
                        }

                        override fun channelRead0(
                            ctx: ChannelHandlerContext,
                            msg: RedisMessage,
                        ) {
                            pipeline.remove(this)
                            pool.release(channel)
                            log.trace(channel) {
                                "Channel released"
                            }
                            responseHandler.responseReceived(msg)
                        }

                        override fun channelInactive(ctx: ChannelHandlerContext) {
                            closeCallback()
                            ctx.fireChannelInactive()
                        }

                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            connectionClosedByTheRemoteServer = false
                            pipeline.remove(this)
                            ctx.close()
                            pool.release(channel)
                            log.trace(channel) {
                                "Channel released after exception"
                            }
                            responseHandler.exceptionCaught(cause)
                        }
                    }

                    channel.pipeline().addLast(handler)
                    response.complete(channel)
                } else {
                    response.completeExceptionally(channelFuture.cause())
                }
            }
        })
        return response
    }

    fun shutDown(): NettyFuture<*> {
        return group.shutdownGracefully()
    }

    override fun close() {
        shutDown().sync()
    }
}
