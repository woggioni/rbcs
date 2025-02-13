package net.woggioni.rbcs.server.memcache.client


import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOption
import io.netty.channel.ChannelPipeline
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.pool.AbstractChannelPoolHandler
import io.netty.channel.pool.ChannelPool
import io.netty.channel.pool.FixedChannelPool
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.memcache.DefaultLastMemcacheContent
import io.netty.handler.codec.memcache.DefaultMemcacheContent
import io.netty.handler.codec.memcache.LastMemcacheContent
import io.netty.handler.codec.memcache.MemcacheContent
import io.netty.handler.codec.memcache.MemcacheObject
import io.netty.handler.codec.memcache.binary.BinaryMemcacheClientCodec
import io.netty.handler.codec.memcache.binary.BinaryMemcacheResponse
import io.netty.handler.logging.LoggingHandler
import io.netty.util.concurrent.GenericFutureListener
import net.woggioni.rbcs.common.HostAndPort
import net.woggioni.rbcs.common.contextLogger
import net.woggioni.rbcs.common.debug
import net.woggioni.rbcs.server.memcache.MemcacheCacheConfiguration
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import io.netty.util.concurrent.Future as NettyFuture


class MemcacheClient(private val cfg: MemcacheCacheConfiguration) : AutoCloseable {

    private companion object {
        @JvmStatic
        private val log = contextLogger()
    }

    private val group: NioEventLoopGroup
    private val connectionPool: MutableMap<HostAndPort, ChannelPool> = ConcurrentHashMap()

    init {
        group = NioEventLoopGroup()
    }

    private val counter = AtomicLong(0)

    private fun newConnectionPool(server: MemcacheCacheConfiguration.Server): FixedChannelPool {
        val bootstrap = Bootstrap().apply {
            group(group)
            channel(NioSocketChannel::class.java)
            option(ChannelOption.SO_KEEPALIVE, true)
            remoteAddress(InetSocketAddress(server.endpoint.host, server.endpoint.port))
            server.connectionTimeoutMillis?.let {
                option(ChannelOption.CONNECT_TIMEOUT_MILLIS, it)
            }
        }
        val channelPoolHandler = object : AbstractChannelPoolHandler() {

            override fun channelCreated(ch: Channel) {
                val pipeline: ChannelPipeline = ch.pipeline()
                pipeline.addLast(BinaryMemcacheClientCodec())
                pipeline.addLast(LoggingHandler())
            }
        }
        return FixedChannelPool(bootstrap, channelPoolHandler, server.maxConnections)
    }

    fun sendRequest(key: ByteBuf, responseHandle: MemcacheResponseHandle): CompletableFuture<MemcacheRequestHandle> {
        val server = cfg.servers.let { servers ->
            if (servers.size > 1) {
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
        }

        val response = CompletableFuture<MemcacheRequestHandle>()
        // Custom handler for processing responses
        val pool = connectionPool.computeIfAbsent(server.endpoint) {
            newConnectionPool(server)
        }
        pool.acquire().addListener(object : GenericFutureListener<NettyFuture<Channel>> {
            override fun operationComplete(channelFuture: NettyFuture<Channel>) {
                if (channelFuture.isSuccess) {
                    val channel = channelFuture.now
                    val pipeline = channel.pipeline()
                    val handler = object : SimpleChannelInboundHandler<MemcacheObject>() {
                        override fun channelRead0(
                            ctx: ChannelHandlerContext,
                            msg: MemcacheObject
                        ) {
                            when (msg) {
                                is BinaryMemcacheResponse -> responseHandle.handleEvent(
                                    StreamingResponseEvent.ResponseReceived(
                                        msg
                                    )
                                )

                                is LastMemcacheContent -> {
                                    responseHandle.handleEvent(
                                        StreamingResponseEvent.LastContentReceived(
                                            msg
                                        )
                                    )
                                    pipeline.removeLast()
                                    pool.release(channel)
                                }

                                is MemcacheContent -> responseHandle.handleEvent(
                                    StreamingResponseEvent.ContentReceived(
                                        msg
                                    )
                                )
                            }
                        }

                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            responseHandle.handleEvent(StreamingResponseEvent.ExceptionCaught(cause))
                            ctx.close()
                            pipeline.removeLast()
                            pool.release(channel)
                        }
                    }
                    channel.pipeline()
                        .addLast("client-handler", handler)
                    response.complete(object : MemcacheRequestHandle {
                        override fun handleEvent(evt: StreamingRequestEvent) {
                            when (evt) {
                                is StreamingRequestEvent.SendRequest -> {
                                    channel.writeAndFlush(evt.request)
                                }

                                is StreamingRequestEvent.SendLastChunk -> {
                                    channel.writeAndFlush(DefaultLastMemcacheContent(evt.chunk))
                                    val value = counter.incrementAndGet()
                                    log.debug {
                                        "Finished request counter: $value"
                                    }
                                }

                                is StreamingRequestEvent.SendChunk -> {
                                    channel.writeAndFlush(DefaultMemcacheContent(evt.chunk))
                                }

                                is StreamingRequestEvent.ExceptionCaught -> {
                                    responseHandle.handleEvent(StreamingResponseEvent.ExceptionCaught(evt.exception))
                                    channel.close()
                                    pipeline.removeLast()
                                    pool.release(channel)
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