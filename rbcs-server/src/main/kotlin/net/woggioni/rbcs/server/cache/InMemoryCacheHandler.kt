package net.woggioni.rbcs.server.cache

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import net.woggioni.rbcs.api.CacheHandler
import net.woggioni.rbcs.api.message.CacheMessage
import net.woggioni.rbcs.api.message.CacheMessage.CacheContent
import net.woggioni.rbcs.api.message.CacheMessage.CacheGetRequest
import net.woggioni.rbcs.api.message.CacheMessage.CachePutRequest
import net.woggioni.rbcs.api.message.CacheMessage.CachePutResponse
import net.woggioni.rbcs.api.message.CacheMessage.CacheValueFoundResponse
import net.woggioni.rbcs.api.message.CacheMessage.CacheValueNotFoundResponse
import net.woggioni.rbcs.api.message.CacheMessage.LastCacheContent
import net.woggioni.rbcs.common.ByteBufOutputStream
import net.woggioni.rbcs.common.RBCS.processCacheKey
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterOutputStream

class InMemoryCacheHandler(
    private val cache: InMemoryCache,
    private val digestAlgorithm: String?,
    private val compressionEnabled: Boolean,
    private val compressionLevel: Int
) : CacheHandler() {

    private interface InProgressRequest : AutoCloseable {
    }

    private class InProgressGetRequest(val request : CacheGetRequest) : InProgressRequest {
        override fun close() {
        }
    }

    private interface InProgressPutRequest : InProgressRequest {
        val request: CachePutRequest
        val buf: ByteBuf

        fun append(buf: ByteBuf)
    }

    private inner class InProgressPlainPutRequest(ctx: ChannelHandlerContext, override val request: CachePutRequest) :
        InProgressPutRequest {
        override val buf = ctx.alloc().compositeBuffer()

        private val stream = ByteBufOutputStream(buf).let {
            if (compressionEnabled) {
                DeflaterOutputStream(it, Deflater(compressionLevel))
            } else {
                it
            }
        }

        override fun append(buf: ByteBuf) {
            this.buf.addComponent(true, buf.retain())
        }

        override fun close() {
            buf.release()
        }
    }

    private inner class InProgressCompressedPutRequest(
        ctx: ChannelHandlerContext,
        override val request: CachePutRequest
    ) : InProgressPutRequest {

        override val buf = ctx.alloc().heapBuffer()

        private val stream = ByteBufOutputStream(buf).let {
            DeflaterOutputStream(it, Deflater(compressionLevel))
        }

        override fun append(buf: ByteBuf) {
            buf.readBytes(stream, buf.readableBytes())
        }

        override fun close() {
            stream.close()
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
        inProgressRequest = InProgressGetRequest(msg)
    }

    private fun handlePutRequest(ctx: ChannelHandlerContext, msg: CachePutRequest) {
        inProgressRequest = if(compressionEnabled) {
            InProgressCompressedPutRequest(ctx, msg)
        } else {
            InProgressPlainPutRequest(ctx, msg)
        }
    }

    private fun handleCacheContent(ctx: ChannelHandlerContext, msg: CacheContent) {
        val req = inProgressRequest
        if(req is InProgressPutRequest) {
            req.append(msg.content())
        }
    }

    private fun handleLastCacheContent(ctx: ChannelHandlerContext, msg: LastCacheContent) {
        handleCacheContent(ctx, msg)
        when(val req = inProgressRequest) {
            is InProgressGetRequest -> {
                cache.get(processCacheKey(req.request.key, digestAlgorithm))?.let { value ->
                    sendMessageAndFlush(ctx, CacheValueFoundResponse(req.request.key, value.metadata))
                    if (compressionEnabled) {
                        val buf = ctx.alloc().heapBuffer()
                        InflaterOutputStream(ByteBufOutputStream(buf)).use {
                            value.content.readBytes(it, value.content.readableBytes())
                            value.content.release()
                            buf.retain()
                        }
                        sendMessage(ctx, LastCacheContent(buf))
                    } else {
                        sendMessage(ctx, LastCacheContent(value.content))
                    }
                } ?: sendMessage(ctx, CacheValueNotFoundResponse())
            }
            is InProgressPutRequest -> {
                this.inProgressRequest = null
                val buf = req.buf
                buf.retain()
                req.close()
                val cacheKey = processCacheKey(req.request.key, digestAlgorithm)
                cache.put(cacheKey, CacheEntry(req.request.metadata, buf))
                sendMessageAndFlush(ctx, CachePutResponse(req.request.key))
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        inProgressRequest?.close()
        inProgressRequest = null
        super.exceptionCaught(ctx, cause)
    }
}