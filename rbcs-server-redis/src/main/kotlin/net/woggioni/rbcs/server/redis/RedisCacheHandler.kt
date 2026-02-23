package net.woggioni.rbcs.server.redis

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.CompositeByteBuf
import io.netty.channel.Channel as NettyChannel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.redis.ArrayRedisMessage
import io.netty.handler.codec.redis.ErrorRedisMessage
import io.netty.handler.codec.redis.FullBulkStringRedisMessage
import io.netty.handler.codec.redis.RedisMessage
import io.netty.handler.codec.redis.SimpleStringRedisMessage

import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.ReadableByteChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterOutputStream

import net.woggioni.rbcs.api.CacheHandler
import net.woggioni.rbcs.api.CacheValueMetadata
import net.woggioni.rbcs.api.exception.ContentTooLargeException
import net.woggioni.rbcs.api.message.CacheMessage
import net.woggioni.rbcs.api.message.CacheMessage.CacheContent
import net.woggioni.rbcs.api.message.CacheMessage.CacheGetRequest
import net.woggioni.rbcs.api.message.CacheMessage.CachePutRequest
import net.woggioni.rbcs.api.message.CacheMessage.CachePutResponse
import net.woggioni.rbcs.api.message.CacheMessage.CacheValueFoundResponse
import net.woggioni.rbcs.api.message.CacheMessage.CacheValueNotFoundResponse
import net.woggioni.rbcs.api.message.CacheMessage.LastCacheContent
import net.woggioni.rbcs.common.ByteBufInputStream
import net.woggioni.rbcs.common.ByteBufOutputStream
import net.woggioni.rbcs.common.RBCS.processCacheKey
import net.woggioni.rbcs.common.RBCS.toIntOrNull
import net.woggioni.rbcs.common.createLogger
import net.woggioni.rbcs.common.debug
import net.woggioni.rbcs.common.extractChunk
import net.woggioni.rbcs.common.trace
import net.woggioni.rbcs.common.warn
import net.woggioni.rbcs.server.redis.client.RedisClient
import net.woggioni.rbcs.server.redis.client.RedisResponseHandler

class RedisCacheHandler(
    private val client: RedisClient,
    private val keyPrefix: String?,
    private val digestAlgorithm: String?,
    private val compressionEnabled: Boolean,
    private val compressionLevel: Int,
    private val chunkSize: Int,
    private val maxAge: Duration,
) : CacheHandler() {
    companion object {
        private val log = createLogger<RedisCacheHandler>()
    }

    private interface InProgressRequest

    private inner class InProgressGetRequest(
        val key: String,
        private val ctx: ChannelHandlerContext,
    ) : InProgressRequest {
        private val chunk = ctx.alloc().compositeBuffer()
        private val outputStream = ByteBufOutputStream(chunk).let {
            if (compressionEnabled) {
                InflaterOutputStream(it)
            } else {
                it
            }
        }

        fun processResponse(data: ByteBuf) {
            if (data.readableBytes() < Int.SIZE_BYTES) {
                log.debug(ctx) {
                    "Received empty or corrupt data from Redis for key $key"
                }
                sendMessageAndFlush(ctx, CacheValueNotFoundResponse(key))
                data.release()
                return
            }

            val metadataSize = data.readInt()
            if (data.readableBytes() < metadataSize) {
                log.debug(ctx) {
                    "Received incomplete metadata from Redis for key $key"
                }
                sendMessageAndFlush(ctx, CacheValueNotFoundResponse(key))
                data.release()
                return
            }

            val metadata = ObjectInputStream(ByteBufInputStream(data)).use {
                data.retain()
                it.readObject() as CacheValueMetadata
            }
            data.readerIndex(Int.SIZE_BYTES + metadataSize)

            log.trace(ctx) {
                "Sending response from cache"
            }
            sendMessageAndFlush(ctx, CacheValueFoundResponse(key, metadata))

            // Decompress and stream the remaining payload
            data.readBytes(outputStream, data.readableBytes())
            data.release()
            commit()
        }

        private fun flush(last: Boolean) {
            val toSend = extractChunk(chunk, ctx.alloc())
            val msg = if (last) {
                log.trace(ctx) {
                    "Sending last chunk to client"
                }
                LastCacheContent(toSend)
            } else {
                log.trace(ctx) {
                    "Sending chunk to client"
                }
                CacheContent(toSend)
            }
            sendMessageAndFlush(ctx, msg)
        }

        fun commit() {
            chunk.retain()
            outputStream.close()
            flush(true)
            chunk.release()
        }

        fun rollback() {
            outputStream.close()
        }
    }

    private inner class InProgressPutRequest(
        private val ch: NettyChannel,
        metadata: CacheValueMetadata,
        val keyString: String,
        val keyBytes: ByteBuf,
        private val alloc: ByteBufAllocator,
    ) : InProgressRequest {
        private var totalSize = 0
        private var tmpFile: FileChannel? = null
        private val accumulator = alloc.compositeBuffer()
        private val stream = ByteBufOutputStream(accumulator).let {
            if (compressionEnabled) {
                DeflaterOutputStream(it, Deflater(compressionLevel))
            } else {
                it
            }
        }

        init {
            ByteArrayOutputStream().let { baos ->
                ObjectOutputStream(baos).use {
                    it.writeObject(metadata)
                }
                val serializedBytes = baos.toByteArray()
                accumulator.writeInt(serializedBytes.size)
                accumulator.writeBytes(serializedBytes)
            }
        }

        fun write(buf: ByteBuf) {
            totalSize += buf.readableBytes()
            buf.readBytes(stream, buf.readableBytes())
            tmpFile?.let {
                flushToDisk(it, accumulator)
            }
            if (accumulator.readableBytes() > 0x100000) {
                log.debug(ch) {
                    "Entry is too big, buffering it into a file"
                }
                val opts = arrayOf(
                    StandardOpenOption.DELETE_ON_CLOSE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
                FileChannel.open(Files.createTempFile("rbcs-server-redis", ".tmp"), *opts).let { fc ->
                    tmpFile = fc
                    flushToDisk(fc, accumulator)
                }
            }
        }

        private fun flushToDisk(fc: FileChannel, buf: CompositeByteBuf) {
            val chunk = extractChunk(buf, alloc)
            fc.write(chunk.nioBuffer())
            chunk.release()
        }

        fun commit(): Pair<Int, ReadableByteChannel> {
            keyBytes.release()
            accumulator.retain()
            stream.close()
            val fileChannel = tmpFile
            return if (fileChannel != null) {
                flushToDisk(fileChannel, accumulator)
                accumulator.release()
                fileChannel.position(0)
                val fileSize = fileChannel.size().toIntOrNull() ?: let {
                    fileChannel.close()
                    throw ContentTooLargeException("Request body is too large", null)
                }
                fileSize to fileChannel
            } else {
                accumulator.readableBytes() to Channels.newChannel(ByteBufInputStream(accumulator))
            }
        }

        fun rollback() {
            stream.close()
            keyBytes.release()
            tmpFile?.close()
        }
    }

    private var inProgressRequest: InProgressRequest? = null

    override fun channelRead0(ctx: ChannelHandlerContext, msg: CacheMessage) {
        when (msg) {
            is CacheGetRequest -> handleGetRequest(ctx, msg)
            is CachePutRequest -> handlePutRequest(ctx, msg)
            is LastCacheContent -> handleLastCacheContent(ctx, msg)
            is CacheContent -> handleCacheContent(ctx, msg)
            else -> ctx.fireChannelRead(msg)
        }
    }

    private fun handleGetRequest(ctx: ChannelHandlerContext, msg: CacheGetRequest) {
        log.debug(ctx) {
            "Fetching ${msg.key} from Redis"
        }
        val keyBytes = processCacheKey(msg.key, keyPrefix, digestAlgorithm)
        val keyString = String(keyBytes, StandardCharsets.UTF_8)
        val responseHandler = object : RedisResponseHandler {
            override fun responseReceived(response: RedisMessage) {
                when (response) {
                    is FullBulkStringRedisMessage -> {
                        if (response === FullBulkStringRedisMessage.NULL_INSTANCE || response.content().readableBytes() == 0) {
                            log.debug(ctx) {
                                "Cache miss for key ${msg.key} on Redis"
                            }
                            sendMessageAndFlush(ctx, CacheValueNotFoundResponse(msg.key))
                        } else {
                            log.debug(ctx) {
                                "Cache hit for key ${msg.key} on Redis"
                            }
                            val getRequest = InProgressGetRequest(msg.key, ctx)
                            inProgressRequest = getRequest
                            getRequest.processResponse(response.content())
                            inProgressRequest = null
                        }
                    }

                    is ErrorRedisMessage -> {
                        this@RedisCacheHandler.exceptionCaught(
                            ctx, RedisException("Redis error for GET ${msg.key}: ${response.content()}")
                        )
                    }

                    else -> {
                        log.warn(ctx) {
                            "Unexpected response type from Redis for key ${msg.key}: ${response.javaClass.name}"
                        }
                        sendMessageAndFlush(ctx, CacheValueNotFoundResponse(msg.key))
                    }
                }
            }

            override fun exceptionCaught(ex: Throwable) {
                this@RedisCacheHandler.exceptionCaught(ctx, ex)
            }
        }
        client.sendCommand(keyBytes, ctx.alloc(), responseHandler).thenAccept { channel ->
            log.trace(ctx) {
                "Sending GET request for key ${msg.key} to Redis"
            }
            val cmd = buildRedisCommand(ctx.alloc(), "GET", keyString)
            channel.writeAndFlush(cmd)
        }
    }

    private fun handlePutRequest(ctx: ChannelHandlerContext, msg: CachePutRequest) {
        val keyBytes = processCacheKey(msg.key, keyPrefix, digestAlgorithm)
        val keyBuf = ctx.alloc().buffer().also {
            it.writeBytes(keyBytes)
        }
        inProgressRequest = InProgressPutRequest(ctx.channel(), msg.metadata, msg.key, keyBuf, ctx.alloc())
    }

    private fun handleCacheContent(ctx: ChannelHandlerContext, msg: CacheContent) {
        val request = inProgressRequest
        when (request) {
            is InProgressPutRequest -> {
                log.trace(ctx) {
                    "Received chunk of ${msg.content().readableBytes()} bytes for Redis"
                }
                request.write(msg.content())
            }

            is InProgressGetRequest -> {
                msg.release()
            }
        }
    }

    private fun handleLastCacheContent(ctx: ChannelHandlerContext, msg: LastCacheContent) {
        val request = inProgressRequest
        when (request) {
            is InProgressPutRequest -> {
                inProgressRequest = null
                log.trace(ctx) {
                    "Received last chunk of ${msg.content().readableBytes()} bytes for Redis"
                }
                request.write(msg.content())
                val keyBytes = processCacheKey(request.keyString, keyPrefix, digestAlgorithm)
                val keyString = String(keyBytes, StandardCharsets.UTF_8)
                val (payloadSize, payloadSource) = request.commit()

                // Read the entire payload into a single ByteBuf for the SET command
                val valueBuf = ctx.alloc().buffer(payloadSize)
                payloadSource.use { source ->
                    val bb = ByteBuffer.allocate(chunkSize)
                    while (true) {
                        val read = source.read(bb)
                        if (read < 0) break
                        bb.flip()
                        valueBuf.writeBytes(bb)
                        bb.clear()
                    }
                }

                val expirySeconds = maxAge.toSeconds().toString()

                val responseHandler = object : RedisResponseHandler {
                    override fun responseReceived(response: RedisMessage) {
                        when (response) {
                            is SimpleStringRedisMessage -> {
                                log.debug(ctx) {
                                    "Inserted key ${request.keyString} into Redis"
                                }
                                sendMessageAndFlush(ctx, CachePutResponse(request.keyString))
                            }

                            is ErrorRedisMessage -> {
                                this@RedisCacheHandler.exceptionCaught(
                                    ctx, RedisException("Redis error for SET ${request.keyString}: ${response.content()}")
                                )
                            }

                            else -> {
                                this@RedisCacheHandler.exceptionCaught(
                                    ctx, RedisException("Unexpected response for SET ${request.keyString}: ${response.javaClass.name}")
                                )
                            }
                        }
                    }

                    override fun exceptionCaught(ex: Throwable) {
                        this@RedisCacheHandler.exceptionCaught(ctx, ex)
                    }
                }

                // Use a ByteBuf key for server selection
                client.sendCommand(keyBytes, ctx.alloc(), responseHandler).thenAccept { channel ->
                    log.trace(ctx) {
                        "Sending SET request to Redis"
                    }
                    // Build SET key value EX seconds
                    val cmd = buildRedisSetCommand(ctx.alloc(), keyString, valueBuf, expirySeconds)
                    channel.writeAndFlush(cmd)
                }.whenComplete { _, ex ->
                    if (ex != null) {
                        valueBuf.release()
                        this@RedisCacheHandler.exceptionCaught(ctx, ex)
                    }
                }
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        val request = inProgressRequest
        when (request) {
            is InProgressPutRequest -> {
                inProgressRequest = null
                request.rollback()
            }

            is InProgressGetRequest -> {
                inProgressRequest = null
                request.rollback()
            }
        }
        super.exceptionCaught(ctx, cause)
    }

    private fun buildRedisCommand(alloc: ByteBufAllocator, vararg args: String): ArrayRedisMessage {
        val children = args.map { arg ->
            FullBulkStringRedisMessage(
                alloc.buffer(arg.toByteArray(StandardCharsets.UTF_8))
            )
        }
        return ArrayRedisMessage(children)
    }

    private fun ByteBufAllocator.buffer(bytes : ByteArray) = buffer().apply {
        writeBytes(bytes)
    }

    private fun buildRedisSetCommand(
        alloc: ByteBufAllocator,
        key: String,
        value: ByteBuf,
        expirySeconds: String,
    ): ArrayRedisMessage {
        val children = listOf(
            FullBulkStringRedisMessage(alloc.buffer("SET".toByteArray(StandardCharsets.UTF_8))),
            FullBulkStringRedisMessage(alloc.buffer(key.toByteArray(StandardCharsets.UTF_8))),
            FullBulkStringRedisMessage(value),
            FullBulkStringRedisMessage(alloc.buffer("EX".toByteArray(StandardCharsets.UTF_8))),
            FullBulkStringRedisMessage(alloc.buffer(expirySeconds.toByteArray(StandardCharsets.UTF_8))),
        )
        return ArrayRedisMessage(children)
    }
}
