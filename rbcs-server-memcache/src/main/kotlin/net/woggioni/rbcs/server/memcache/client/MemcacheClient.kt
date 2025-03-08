package net.woggioni.rbcs.server.memcache.client


import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
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
import io.netty.handler.codec.memcache.LastMemcacheContent
import io.netty.handler.codec.memcache.MemcacheContent
import io.netty.handler.codec.memcache.MemcacheObject
import io.netty.handler.codec.memcache.binary.BinaryMemcacheClientCodec
import io.netty.handler.codec.memcache.binary.BinaryMemcacheRequest
import io.netty.handler.codec.memcache.binary.BinaryMemcacheResponse
import io.netty.util.concurrent.GenericFutureListener
import net.woggioni.rbcs.common.HostAndPort
import net.woggioni.rbcs.common.createLogger
import net.woggioni.rbcs.common.trace
import net.woggioni.rbcs.server.memcache.MemcacheCacheConfiguration
import net.woggioni.rbcs.server.memcache.MemcacheCacheHandler
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import io.netty.util.concurrent.Future as NettyFuture


class MemcacheClient(
    private val servers: List<MemcacheCacheConfiguration.Server>,
    private val chunkSize : Int,
    private val group: EventLoopGroup,
    private val channelFactory: ChannelFactory<SocketChannel>,
    private val connectionPool: ConcurrentHashMap<HostAndPort, FixedChannelPool>
) : AutoCloseable {

    private companion object {
        private val log = createLogger<MemcacheCacheHandler>()
    }

    private fun newConnectionPool(server: MemcacheCacheConfiguration.Server): FixedChannelPool {
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
                pipeline.addLast(BinaryMemcacheClientCodec(chunkSize, true))
            }
        }
        return FixedChannelPool(bootstrap, channelPoolHandler, server.maxConnections)
    }

    fun sendRequest(
        key: ByteBuf,
        responseHandler: MemcacheResponseHandler
    ): CompletableFuture<MemcacheRequestController> {
        val server = if (servers.size > 1) {
            var checksum = 0
            while (key.readableBytes() > 4) {
                val byte = key.readInt()
                checksum = checksum xor byte
            }
            while (key.readableBytes() > 0) {
                val byte = key.readByte()
                checksum = checksum xor byte.toInt()
            }
            servers[checksum % servers.size]
        } else {
            servers.first()
        }
        key.release()

        val response = CompletableFuture<MemcacheRequestController>()
        // Custom handler for processing responses
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
                            val ex = IOException("The memcache server closed the connection")
                            val completed = response.completeExceptionally(ex)
                            if(!completed) responseHandler.exceptionCaught(ex)
                        }
                    }
                    val closeListener = ChannelFutureListener {
                        closeCallback()
                    }
                    channel.closeFuture().addListener(closeListener)
                    val pipeline = channel.pipeline()
                    val handler = object : SimpleChannelInboundHandler<MemcacheObject>() {

                        override fun handlerAdded(ctx: ChannelHandlerContext) {
                            channel.closeFuture().removeListener(closeListener)
                        }

                        override fun channelRead0(
                            ctx: ChannelHandlerContext,
                            msg: MemcacheObject
                        ) {
                            when (msg) {
                                is BinaryMemcacheResponse -> {
                                    responseHandler.responseReceived(msg)
                                }

                                is LastMemcacheContent -> {
                                    responseHandler.contentReceived(msg)
                                    pipeline.remove(this)
                                }

                                is MemcacheContent -> {
                                    responseHandler.contentReceived(msg)
                                }
                            }
                        }

                        override fun channelInactive(ctx: ChannelHandlerContext) {
                            closeCallback()
                            ctx.fireChannelInactive()
                        }

                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            connectionClosedByTheRemoteServer = false
                            ctx.close()
                            responseHandler.exceptionCaught(cause)
                        }
                    }

                    channel.pipeline().addLast(handler)
                    response.complete(object : MemcacheRequestController {
                        private var channelReleased = false

                        override fun sendRequest(request: BinaryMemcacheRequest) {
                            channel.writeAndFlush(request)
                        }

                        override fun sendContent(content: MemcacheContent) {
                            channel.writeAndFlush(content).addListener {
                                if(content is LastMemcacheContent) {
                                    if(!channelReleased) {
                                        pool.release(channel)
                                        channelReleased = true
                                        log.trace(channel) {
                                            "Channel released"
                                        }
                                    }
                                }
                            }
                        }

                        override fun exceptionCaught(ex: Throwable) {
                            log.warn(ex.message, ex)
                            connectionClosedByTheRemoteServer = false
                            channel.close()
                            if(!channelReleased) {
                                pool.release(channel)
                                channelReleased = true
                                log.trace(channel) {
                                    "Channel released"
                                }
                            }
                        }
                    })
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