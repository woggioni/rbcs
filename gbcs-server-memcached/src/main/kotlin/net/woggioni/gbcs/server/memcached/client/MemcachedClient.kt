package net.woggioni.gbcs.server.memcached.client

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
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
import io.netty.handler.codec.DecoderException
import io.netty.handler.codec.memcache.DefaultLastMemcacheContent
import io.netty.handler.codec.memcache.DefaultMemcacheContent
import io.netty.handler.codec.memcache.LastMemcacheContent
import io.netty.handler.codec.memcache.MemcacheContent
import io.netty.handler.codec.memcache.MemcacheObject
import io.netty.handler.codec.memcache.binary.BinaryMemcacheClientCodec
import io.netty.handler.codec.memcache.binary.BinaryMemcacheOpcodes
import io.netty.handler.codec.memcache.binary.BinaryMemcacheRequest
import io.netty.handler.codec.memcache.binary.BinaryMemcacheResponse
import io.netty.handler.codec.memcache.binary.BinaryMemcacheResponseStatus
import io.netty.handler.codec.memcache.binary.DefaultBinaryMemcacheRequest
import io.netty.util.concurrent.GenericFutureListener
import net.woggioni.gbcs.common.GBCS.digest
import net.woggioni.gbcs.common.HostAndPort
import net.woggioni.gbcs.common.contextLogger
import net.woggioni.gbcs.server.memcached.MemcachedCacheConfiguration
import net.woggioni.gbcs.server.memcached.MemcachedException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import io.netty.util.concurrent.Future as NettyFuture


class MemcachedClient(private val cfg: MemcachedCacheConfiguration) : AutoCloseable {

    private val log = contextLogger()
    private val group: NioEventLoopGroup
    private val connectionPool: MutableMap<HostAndPort, ChannelPool> = ConcurrentHashMap()

    init {
        group = NioEventLoopGroup()
    }

    private fun newConnectionPool(server : MemcachedCacheConfiguration.Server) : FixedChannelPool {
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
            }
        }
        return FixedChannelPool(bootstrap, channelPoolHandler, server.maxConnections)
    }


    private fun sendRequest(request: BinaryMemcacheRequest,
                            responseListener: ResponseListener?
    ): CompletableFuture<CallHandle> {

        val server = cfg.servers.let { servers ->
            if(servers.size > 1) {
                val key = request.key().duplicate()
                var checksum = 0
                while(key.readableBytes() > 4) {
                    val byte = key.readInt()
                    checksum = checksum xor byte
                }
                while(key.readableBytes() > 0) {
                    val byte = key.readByte()
                    checksum = checksum xor byte.toInt()
                }
                servers[checksum % servers.size]
            } else {
                servers.first()
            }
        }

        val callHandleFuture = CompletableFuture<CallHandle>()
        val result = CompletableFuture<Short>()
        // Custom handler for processing responses
        val pool = connectionPool.computeIfAbsent(server.endpoint) {
            newConnectionPool(server)
        }
        pool.acquire().addListener(object : GenericFutureListener<NettyFuture<Channel>> {
            override fun operationComplete(channelFuture: NettyFuture<Channel>) {
                if (channelFuture.isSuccess) {
                    val channel = channelFuture.now
                    val pipeline = channel.pipeline()
                    channel.pipeline().addLast("handler", object : SimpleChannelInboundHandler<MemcacheObject>() {
                        val response : MemcacheResponse? = null
                        override fun channelRead0(
                            ctx: ChannelHandlerContext,
                            msg: MemcacheObject
                        ) {
                            if(msg is BinaryMemcacheResponse) {
                                val resp = MemcacheResponse.of(msg)
                                responseListener?.listen(ResponseEvent.ResponseReceived(resp))
                                if(msg.totalBodyLength() == msg.keyLength() + msg.extrasLength()) {
                                    result.complete(resp.status)
                                }
                            }
                            if(responseListener != null) {
                                when (msg) {
                                    is LastMemcacheContent -> {
                                        responseListener.listen(ResponseEvent.LastResponseContentChunkReceived(msg.content().nioBuffer()))
                                        result.complete(response?.status)
                                        pipeline.removeLast()
                                        pool.release(channel)
                                    }
                                    is MemcacheContent ->  {
                                        responseListener.listen(ResponseEvent.ResponseContentChunkReceived(msg.content().nioBuffer()))
                                    }
                                }
                            }
                        }

                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            val ex = when (cause) {
                                is DecoderException -> cause.cause!!
                                else -> cause
                            }
                            responseListener?.listen(ResponseEvent.ExceptionCaught(ex))
                            result.completeExceptionally(ex)
                            ctx.close()
                            pipeline.removeLast()
                            pool.release(channel)
                        }
                    })

                    val chunks = mutableListOf <ByteBuffer>()
                    fun sendRequest() {
                        val valueLen = chunks.fold(0) { acc : Int, c2 : ByteBuffer ->
                            acc + c2.remaining()
                        }
                        request.setTotalBodyLength(request.keyLength() + request.extrasLength() + valueLen)
                        channel.write(request)
                        for((i, chunk) in chunks.withIndex()) {
                            if(i + 1 < chunks.size) {
                                channel.write(DefaultMemcacheContent(Unpooled.wrappedBuffer(chunk)))
                            } else {
                                channel.write(DefaultLastMemcacheContent(Unpooled.wrappedBuffer(chunk)))
                            }
                        }
                        channel.flush()
                    }

                    callHandleFuture.complete(object : CallHandle {
                        override fun sendChunk(requestBodyChunk: ByteBuffer) {
                                chunks.addLast(requestBodyChunk)
                        }

                        override fun waitForResponse(): CompletableFuture<Short> {
                            sendRequest()
                            return result
                        }
                    })
                } else {
                    callHandleFuture.completeExceptionally(channelFuture.cause())
                }
            }
        })
        return callHandleFuture
    }

    private fun encodeExpiry(expiry: Duration) : Int {
        val expirySeconds = expiry.toSeconds()
        return expirySeconds.toInt().takeIf { it.toLong() == expirySeconds }
            ?: Instant.ofEpochSecond(expirySeconds).epochSecond.toInt()
    }

    fun get(key: String, responseListener: ResponseListener) : CompletableFuture<CallHandle> {
        val request = (cfg.digestAlgorithm
            ?.let(MessageDigest::getInstance)
            ?.let { md ->
                digest(key.toByteArray(), md)
            } ?: key.toByteArray(Charsets.UTF_8)).let { digest ->
            DefaultBinaryMemcacheRequest().apply {
                setKey(Unpooled.wrappedBuffer(digest))
                setOpcode(BinaryMemcacheOpcodes.GET)
            }
        }
        return sendRequest(request, responseListener)
    }

    fun put(key: String, expiry : Duration, cas : Long? = null): CompletableFuture<CallHandle> {
        val request = (cfg.digestAlgorithm
            ?.let(MessageDigest::getInstance)
            ?.let { md ->
                digest(key.toByteArray(), md)
            } ?: key.toByteArray(Charsets.UTF_8)).let { digest ->
            val extras = Unpooled.buffer(8, 8)
            extras.writeInt(0)
            extras.writeInt(encodeExpiry(expiry))
            DefaultBinaryMemcacheRequest().apply {
                setExtras(extras)
                setKey(Unpooled.wrappedBuffer(digest))
                setOpcode(BinaryMemcacheOpcodes.SET)
                cas?.let(this::setCas)
            }
        }
        return sendRequest(request) { evt ->
            when (evt) {
                is ResponseEvent.ResponseReceived -> {
                    if (evt.response.status != BinaryMemcacheResponseStatus.SUCCESS) {
                        throw MemcachedException(evt.response.status)
                    }
                }
                else -> {}
            }
        }
    }



    fun shutDown(): NettyFuture<*> {
        return group.shutdownGracefully()
    }

    override fun close() {
        shutDown().sync()
    }
}