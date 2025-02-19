package net.woggioni.rbcs.server.memcache

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.CompositeByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.memcache.DefaultLastMemcacheContent
import io.netty.handler.codec.memcache.DefaultMemcacheContent
import io.netty.handler.codec.memcache.LastMemcacheContent
import io.netty.handler.codec.memcache.MemcacheContent
import io.netty.handler.codec.memcache.binary.BinaryMemcacheOpcodes
import io.netty.handler.codec.memcache.binary.BinaryMemcacheResponse
import io.netty.handler.codec.memcache.binary.BinaryMemcacheResponseStatus
import io.netty.handler.codec.memcache.binary.DefaultBinaryMemcacheRequest
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
import net.woggioni.rbcs.server.memcache.client.MemcacheClient
import net.woggioni.rbcs.server.memcache.client.MemcacheRequestController
import net.woggioni.rbcs.server.memcache.client.MemcacheResponseHandler
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.ReadableByteChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterOutputStream
import io.netty.channel.Channel as NettyChannel

class MemcacheCacheHandler(
    private val client: MemcacheClient,
    private val digestAlgorithm: String?,
    private val compressionEnabled: Boolean,
    private val compressionLevel: Int,
    private val chunkSize: Int,
    private val maxAge: Duration
) : SimpleChannelInboundHandler<CacheMessage>() {
    companion object {
        private val log = createLogger<MemcacheCacheHandler>()

        private fun encodeExpiry(expiry: Duration): Int {
            val expirySeconds = expiry.toSeconds()
            return expirySeconds.toInt().takeIf { it.toLong() == expirySeconds }
                ?: Instant.ofEpochSecond(expirySeconds).epochSecond.toInt()
        }
    }

    private inner class InProgressGetRequest(
        private val key: String,
        private val ctx: ChannelHandlerContext
    ) {
        private val acc = ctx.alloc().compositeBuffer()
        private val chunk = ctx.alloc().compositeBuffer()
        private val outputStream = ByteBufOutputStream(chunk).let {
            if (compressionEnabled) {
                InflaterOutputStream(it)
            } else {
                it
            }
        }
        private var responseSent = false
        private var metadataSize: Int? = null

        fun write(buf: ByteBuf) {
            acc.addComponent(true, buf.retain())
            if (metadataSize == null && acc.readableBytes() >= Int.SIZE_BYTES) {
                metadataSize = acc.readInt()
            }
            metadataSize
                ?.takeIf { !responseSent }
                ?.takeIf { acc.readableBytes() >= it }
                ?.let { mSize ->
                    val metadata = ObjectInputStream(ByteBufInputStream(acc)).use {
                        acc.retain()
                        it.readObject() as CacheValueMetadata
                    }
                    ctx.writeAndFlush(CacheValueFoundResponse(key, metadata))
                    responseSent = true
                    acc.readerIndex(Int.SIZE_BYTES + mSize)
                }
            if (responseSent) {
                acc.readBytes(outputStream, acc.readableBytes())
                if(acc.readableBytes() >= chunkSize) {
                    flush(false)
                }
            }
        }

        private fun flush(last : Boolean) {
            val toSend = extractChunk(chunk, ctx.alloc())
            val msg = if(last) {
                log.trace(ctx) {
                    "Sending last chunk to client on channel ${ctx.channel().id().asShortText()}"
                }
                LastCacheContent(toSend)
            } else {
                log.trace(ctx) {
                    "Sending chunk to client on channel ${ctx.channel().id().asShortText()}"
                }
                CacheContent(toSend)
            }
            ctx.writeAndFlush(msg)
        }

        fun commit() {
            acc.release()
            chunk.retain()
            outputStream.close()
            flush(true)
            chunk.release()
        }

        fun rollback() {
            acc.release()
            outputStream.close()
        }
    }

    private inner class InProgressPutRequest(
        private val ch : NettyChannel,
        metadata : CacheValueMetadata,
        val digest : ByteBuf,
        val requestController: CompletableFuture<MemcacheRequestController>,
        private val alloc: ByteBufAllocator
    ) {
        private var totalSize = 0
        private var tmpFile : FileChannel? = null
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
            if(accumulator.readableBytes() > 0x100000) {
                log.debug(ch) {
                    "Entry is too big, buffering it into a file"
                }
                val opts = arrayOf(
                    StandardOpenOption.DELETE_ON_CLOSE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
                FileChannel.open(Files.createTempFile("rbcs-memcache", ".tmp"), *opts).let { fc ->
                    tmpFile = fc
                    flushToDisk(fc, accumulator)
                }
            }
        }

        private fun flushToDisk(fc : FileChannel, buf : CompositeByteBuf) {
            val chunk = extractChunk(buf, alloc)
            fc.write(chunk.nioBuffer())
            chunk.release()
        }

        fun commit() : Pair<Int, ReadableByteChannel> {
            digest.release()
            accumulator.retain()
            stream.close()
            val fileChannel = tmpFile
            return if(fileChannel != null) {
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
            digest.release()
            tmpFile?.close()
        }
    }

    private var inProgressPutRequest: InProgressPutRequest? = null
    private var inProgressGetRequest: InProgressGetRequest? = null

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
            "Fetching ${msg.key} from memcache"
        }
        val key = ctx.alloc().buffer().also {
            it.writeBytes(processCacheKey(msg.key, digestAlgorithm))
        }
        val responseHandler = object : MemcacheResponseHandler {
            override fun responseReceived(response: BinaryMemcacheResponse) {
                val status = response.status()
                when (status) {
                    BinaryMemcacheResponseStatus.SUCCESS -> {
                        log.debug(ctx) {
                            "Cache hit for key ${msg.key} on memcache"
                        }
                        inProgressGetRequest = InProgressGetRequest(msg.key, ctx)
                    }

                    BinaryMemcacheResponseStatus.KEY_ENOENT -> {
                        log.debug(ctx) {
                            "Cache miss for key ${msg.key} on memcache"
                        }
                        ctx.writeAndFlush(CacheValueNotFoundResponse())
                    }
                }
            }

            override fun contentReceived(content: MemcacheContent) {
                log.trace(ctx) {
                    "${if(content is LastMemcacheContent) "Last chunk" else "Chunk"} of ${content.content().readableBytes()} bytes received from memcache for key ${msg.key}"
                }
                inProgressGetRequest?.write(content.content())
                if (content is LastMemcacheContent) {
                    inProgressGetRequest?.commit()
                }
            }

            override fun exceptionCaught(ex: Throwable) {
                inProgressGetRequest?.let {
                    inProgressGetRequest = null
                    it.rollback()
                }
                this@MemcacheCacheHandler.exceptionCaught(ctx, ex)
            }
        }
        client.sendRequest(key.retainedDuplicate(), responseHandler).thenAccept { requestHandle ->
            log.trace(ctx) {
                "Sending GET request for key ${msg.key} to memcache"
            }
            val request = DefaultBinaryMemcacheRequest(key).apply {
                setOpcode(BinaryMemcacheOpcodes.GET)
            }
            requestHandle.sendRequest(request)
        }
    }

    private fun handlePutRequest(ctx: ChannelHandlerContext, msg: CachePutRequest) {
        val key = ctx.alloc().buffer().also {
            it.writeBytes(processCacheKey(msg.key, digestAlgorithm))
        }
        val responseHandler = object : MemcacheResponseHandler {
            override fun responseReceived(response: BinaryMemcacheResponse) {
                val status = response.status()
                when (status) {
                    BinaryMemcacheResponseStatus.SUCCESS -> {
                        log.debug(ctx) {
                            "Inserted key ${msg.key} into memcache"
                        }
                        ctx.writeAndFlush(CachePutResponse(msg.key))
                    }
                    else -> this@MemcacheCacheHandler.exceptionCaught(ctx, MemcacheException(status))
                }
            }

            override fun contentReceived(content: MemcacheContent) {}

            override fun exceptionCaught(ex: Throwable) {
                this@MemcacheCacheHandler.exceptionCaught(ctx, ex)
            }
        }

        val requestController = client.sendRequest(key.retainedDuplicate(), responseHandler).whenComplete { _, ex ->
            ex?.let {
                this@MemcacheCacheHandler.exceptionCaught(ctx, ex)
            }
        }
        inProgressPutRequest = InProgressPutRequest(ctx.channel(), msg.metadata, key, requestController, ctx.alloc())
    }

    private fun handleCacheContent(ctx: ChannelHandlerContext, msg: CacheContent) {
        inProgressPutRequest?.let { request ->
            log.trace(ctx) {
                "Received chunk of ${msg.content().readableBytes()} bytes for memcache"
            }
            request.write(msg.content())
        }
    }

    private fun handleLastCacheContent(ctx: ChannelHandlerContext, msg: LastCacheContent) {
        inProgressPutRequest?.let { request ->
            inProgressPutRequest = null
            log.trace(ctx) {
                "Received last chunk of ${msg.content().readableBytes()} bytes for memcache"
            }
            request.write(msg.content())
            val key = request.digest.retainedDuplicate()
            val (payloadSize, payloadSource) = request.commit()
            val extras = ctx.alloc().buffer(8, 8)
            extras.writeInt(0)
            extras.writeInt(encodeExpiry(maxAge))
            val totalBodyLength = request.digest.readableBytes() + extras.readableBytes() + payloadSize
            request.requestController.whenComplete { requestController, ex ->
                if(ex == null) {
                    log.trace(ctx) {
                        "Sending SET request to memcache"
                    }
                    requestController.sendRequest(DefaultBinaryMemcacheRequest().apply {
                        setOpcode(BinaryMemcacheOpcodes.SET)
                        setKey(key)
                        setExtras(extras)
                        setTotalBodyLength(totalBodyLength)
                    })
                    log.trace(ctx) {
                        "Sending request payload to memcache"
                    }
                    payloadSource.use { source ->
                        val bb = ByteBuffer.allocate(chunkSize)
                        while (true) {
                            val read = source.read(bb)
                            bb.limit()
                            if(read >= 0 && bb.position() < chunkSize && bb.hasRemaining()) {
                                continue
                            }
                            val chunk = ctx.alloc().buffer(chunkSize)
                            bb.flip()
                            chunk.writeBytes(bb)
                            bb.clear()
                            log.trace(ctx) {
                                "Sending ${chunk.readableBytes()} bytes chunk to memcache"
                            }
                            if(read < 0) {
                                requestController.sendContent(DefaultLastMemcacheContent(chunk))
                                break
                            } else {
                                requestController.sendContent(DefaultMemcacheContent(chunk))
                            }
                        }
                    }
                } else {
                    payloadSource.close()
                }
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        inProgressGetRequest?.let {
            inProgressGetRequest = null
            it.rollback()
        }
        inProgressPutRequest?.let {
            inProgressPutRequest = null
            it.requestController.thenAccept { controller ->
                controller.exceptionCaught(cause)
            }
            it.rollback()
        }
        super.exceptionCaught(ctx, cause)
    }
}