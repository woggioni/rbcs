package net.woggioni.rbcs.server.memcache.client


import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOption
import io.netty.channel.ChannelPipeline
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.pool.AbstractChannelPoolHandler
import io.netty.channel.pool.ChannelPool
import io.netty.channel.pool.FixedChannelPool
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.memcache.LastMemcacheContent
import io.netty.handler.codec.memcache.MemcacheContent
import io.netty.handler.codec.memcache.MemcacheObject
import io.netty.handler.codec.memcache.binary.BinaryMemcacheClientCodec
import io.netty.handler.codec.memcache.binary.BinaryMemcacheRequest
import io.netty.handler.codec.memcache.binary.BinaryMemcacheResponse
import io.netty.util.concurrent.GenericFutureListener
import net.woggioni.rbcs.common.HostAndPort
import net.woggioni.rbcs.common.createLogger
import net.woggioni.rbcs.common.warn
import net.woggioni.rbcs.server.memcache.MemcacheCacheConfiguration
import net.woggioni.rbcs.server.memcache.MemcacheCacheHandler
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import io.netty.util.concurrent.Future as NettyFuture


class MemcacheClient(private val servers: List<MemcacheCacheConfiguration.Server>, private val chunkSize : Int) : AutoCloseable {

    private companion object {
        private val log = createLogger<MemcacheCacheHandler>()
    }

    private val group: NioEventLoopGroup
    private val connectionPool: MutableMap<HostAndPort, ChannelPool> = ConcurrentHashMap()

    init {
        group = NioEventLoopGroup()
    }

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

                    var requestSent = false
                    var requestBodySent = false
                    var requestFinished = false
                    var responseReceived = false
                    var responseBodyReceived = false
                    var responseFinished = false
                    var requestBodySize = 0
                    var requestBodyBytesSent = 0



                    val channel = channelFuture.now
                    var connectionClosedByTheRemoteServer = true
                    val closeCallback = {
                        if (connectionClosedByTheRemoteServer) {
                            val ex = IOException("The memcache server closed the connection")
                            val completed = response.completeExceptionally(ex)
                            if(!completed) responseHandler.exceptionCaught(ex)
                            log.warn {
                                "RequestSent: $requestSent, RequestBodySent: $requestBodySent, " +
                                "RequestFinished: $requestFinished, ResponseReceived: $responseReceived, " +
                                "ResponseBodyReceived: $responseBodyReceived, ResponseFinished: $responseFinished, " +
                                "RequestBodySize: $requestBodySize, RequestBodyBytesSent: $requestBodyBytesSent"
                            }
                        }
                        pool.release(channel)
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
                                    responseReceived = true
                                }

                                is LastMemcacheContent -> {
                                    responseFinished = true
                                    responseHandler.contentReceived(msg)
                                    pipeline.remove(this)
                                    pool.release(channel)
                                }

                                is MemcacheContent -> {
                                    responseBodyReceived = true
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
                            pool.release(channel)
                            responseHandler.exceptionCaught(cause)
                        }
                    }

                    channel.pipeline()
                        .addLast("client-handler", handler)
                    response.complete(object : MemcacheRequestController {

                        override fun sendRequest(request: BinaryMemcacheRequest) {
                            requestBodySize = request.totalBodyLength() - request.keyLength() - request.extrasLength()
                            channel.writeAndFlush(request)
                            requestSent = true
                        }

                        override fun sendContent(content: MemcacheContent) {
                            val size = content.content().readableBytes()
                            channel.writeAndFlush(content).addListener {
                                requestBodyBytesSent += size
                                requestBodySent = true
                                if(content is LastMemcacheContent) {
                                    requestFinished = true
                                }
                            }
                        }

                        override fun exceptionCaught(ex: Throwable) {
                            connectionClosedByTheRemoteServer = false
                            channel.close()
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