package net.woggioni.gbcs.server.memcache.client


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
import io.netty.handler.codec.memcache.binary.BinaryMemcacheClientCodec
import io.netty.handler.codec.memcache.binary.BinaryMemcacheObjectAggregator
import io.netty.handler.codec.memcache.binary.BinaryMemcacheOpcodes
import io.netty.handler.codec.memcache.binary.BinaryMemcacheResponseStatus
import io.netty.handler.codec.memcache.binary.DefaultFullBinaryMemcacheRequest
import io.netty.handler.codec.memcache.binary.FullBinaryMemcacheRequest
import io.netty.handler.codec.memcache.binary.FullBinaryMemcacheResponse
import io.netty.util.concurrent.GenericFutureListener
import net.woggioni.gbcs.common.ByteBufInputStream
import net.woggioni.gbcs.common.GBCS.digest
import net.woggioni.gbcs.common.HostAndPort
import net.woggioni.gbcs.common.contextLogger
import net.woggioni.gbcs.server.memcache.MemcacheCacheConfiguration
import net.woggioni.gbcs.server.memcache.MemcacheException
import net.woggioni.jwo.JWO
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream
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
                pipeline.addLast(BinaryMemcacheObjectAggregator(Integer.MAX_VALUE))
            }
        }
        return FixedChannelPool(bootstrap, channelPoolHandler, server.maxConnections)
    }


    private fun sendRequest(request: FullBinaryMemcacheRequest): CompletableFuture<FullBinaryMemcacheResponse> {

        val server = cfg.servers.let { servers ->
            if (servers.size > 1) {
                val key = request.key().duplicate()
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

        val response = CompletableFuture<FullBinaryMemcacheResponse>()
        // Custom handler for processing responses
        val pool = connectionPool.computeIfAbsent(server.endpoint) {
            newConnectionPool(server)
        }
        pool.acquire().addListener(object : GenericFutureListener<NettyFuture<Channel>> {
            override fun operationComplete(channelFuture: NettyFuture<Channel>) {
                if (channelFuture.isSuccess) {
                    val channel = channelFuture.now
                    val pipeline = channel.pipeline()
                    channel.pipeline()
                        .addLast("client-handler", object : SimpleChannelInboundHandler<FullBinaryMemcacheResponse>() {
                            override fun channelRead0(
                                ctx: ChannelHandlerContext,
                                msg: FullBinaryMemcacheResponse
                            ) {
                                pipeline.removeLast()
                                pool.release(channel)
                                msg.touch("The method's caller must remember to release this")
                                response.complete(msg.retain())
                            }

                            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                                val ex = when (cause) {
                                    is DecoderException -> cause.cause!!
                                    else -> cause
                                }
                                ctx.close()
                                pipeline.removeLast()
                                pool.release(channel)
                                response.completeExceptionally(ex)
                            }
                        })
                    channel.writeAndFlush(request)
                } else {
                    response.completeExceptionally(channelFuture.cause())
                }
            }
        })
        return response
    }

    private fun encodeExpiry(expiry: Duration): Int {
        val expirySeconds = expiry.toSeconds()
        return expirySeconds.toInt().takeIf { it.toLong() == expirySeconds }
            ?: Instant.ofEpochSecond(expirySeconds).epochSecond.toInt()
    }

    fun get(key: String): CompletableFuture<ReadableByteChannel?> {
        val request = (cfg.digestAlgorithm
            ?.let(MessageDigest::getInstance)
            ?.let { md ->
                digest(key.toByteArray(), md)
            } ?: key.toByteArray(Charsets.UTF_8)).let { digest ->
            DefaultFullBinaryMemcacheRequest(Unpooled.wrappedBuffer(digest), null).apply {
                setOpcode(BinaryMemcacheOpcodes.GET)
            }
        }
        return sendRequest(request).thenApply { response ->
            when (val status = response.status()) {
                BinaryMemcacheResponseStatus.SUCCESS -> {
                    val compressionMode = cfg.compressionMode
                    val content = response.content().retain()
                    response.release()
                    if (compressionMode != null) {
                        when (compressionMode) {
                            MemcacheCacheConfiguration.CompressionMode.GZIP -> {
                                GZIPInputStream(ByteBufInputStream(content))
                            }

                            MemcacheCacheConfiguration.CompressionMode.DEFLATE -> {
                                InflaterInputStream(ByteBufInputStream(content))
                            }
                        }
                    } else {
                        ByteBufInputStream(content)
                    }.let(Channels::newChannel)
                }
                BinaryMemcacheResponseStatus.KEY_ENOENT -> {
                    null
                }
                else -> throw MemcacheException(status)
            }
        }
    }

    fun put(key: String, content: ByteBuf, expiry: Duration, cas: Long? = null): CompletableFuture<Void> {
        val request = (cfg.digestAlgorithm
            ?.let(MessageDigest::getInstance)
            ?.let { md ->
                digest(key.toByteArray(), md)
            } ?: key.toByteArray(Charsets.UTF_8)).let { digest ->
            val extras = Unpooled.buffer(8, 8)
            extras.writeInt(0)
            extras.writeInt(encodeExpiry(expiry))
            val compressionMode = cfg.compressionMode
            val payload = if (compressionMode != null) {
                val inputStream = ByteBufInputStream(Unpooled.wrappedBuffer(content))
                val baos = ByteArrayOutputStream()
                val outputStream = when (compressionMode) {
                    MemcacheCacheConfiguration.CompressionMode.GZIP -> {
                        GZIPOutputStream(baos)
                    }

                    MemcacheCacheConfiguration.CompressionMode.DEFLATE -> {
                        DeflaterOutputStream(baos, Deflater(Deflater.DEFAULT_COMPRESSION, false))
                    }
                }
                inputStream.use { i ->
                    outputStream.use { o ->
                        JWO.copy(i, o)
                    }
                }
                Unpooled.wrappedBuffer(baos.toByteArray())
            } else {
                content
            }
            DefaultFullBinaryMemcacheRequest(Unpooled.wrappedBuffer(digest), extras, payload).apply {
                setOpcode(BinaryMemcacheOpcodes.SET)
                cas?.let(this::setCas)
            }
        }
        return sendRequest(request).thenApply { response ->
            try {
                when (val status = response.status()) {
                    BinaryMemcacheResponseStatus.SUCCESS -> null
                    else -> throw MemcacheException(status)
                }
            } finally {
                response.release()
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