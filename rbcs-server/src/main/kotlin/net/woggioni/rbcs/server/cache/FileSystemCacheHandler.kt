package net.woggioni.rbcs.server.cache

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.stream.ChunkedNioFile
import net.woggioni.rbcs.api.message.CacheMessage
import net.woggioni.rbcs.api.message.CacheMessage.CacheContent
import net.woggioni.rbcs.api.message.CacheMessage.CacheGetRequest
import net.woggioni.rbcs.api.message.CacheMessage.CachePutRequest
import net.woggioni.rbcs.api.message.CacheMessage.CachePutResponse
import net.woggioni.rbcs.api.message.CacheMessage.CacheValueFoundResponse
import net.woggioni.rbcs.api.message.CacheMessage.CacheValueNotFoundResponse
import net.woggioni.rbcs.api.message.CacheMessage.LastCacheContent
import net.woggioni.rbcs.common.RBCS.processCacheKey
import java.nio.channels.Channels
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

class FileSystemCacheHandler(
    private val cache: FileSystemCache,
    private val digestAlgorithm: String?,
    private val compressionEnabled: Boolean,
    private val compressionLevel: Int,
    private val chunkSize: Int
) : SimpleChannelInboundHandler<CacheMessage>() {

    private inner class InProgressPutRequest(
        val key : String,
        private val fileSink : FileSystemCache.FileSink
    ) {

        private val stream = Channels.newOutputStream(fileSink.channel).let {
            if (compressionEnabled) {
                DeflaterOutputStream(it, Deflater(compressionLevel))
            } else {
                it
            }
        }

        fun write(buf: ByteBuf) {
            buf.readBytes(stream, buf.readableBytes())
        }

        fun commit() {
            stream.close()
            fileSink.commit()
        }

        fun rollback() {
            fileSink.rollback()
        }
    }

    private var inProgressPutRequest: InProgressPutRequest? = null

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
        val key = String(Base64.getUrlEncoder().encode(processCacheKey(msg.key, digestAlgorithm)))
        cache.get(key)?.also { entryValue ->
            ctx.writeAndFlush(CacheValueFoundResponse(msg.key, entryValue.metadata))
            entryValue.channel.let { channel ->
                if(compressionEnabled) {
                    InflaterInputStream(Channels.newInputStream(channel)).use { stream ->

                        outerLoop@
                        while (true) {
                            val buf = ctx.alloc().heapBuffer(chunkSize)
                            while(buf.readableBytes() < chunkSize) {
                                val read = buf.writeBytes(stream, chunkSize)
                                if(read < 0) {
                                    ctx.writeAndFlush(LastCacheContent(buf))
                                    break@outerLoop
                                }
                            }
                            ctx.writeAndFlush(CacheContent(buf))
                        }
                    }
                } else {
                    ctx.writeAndFlush(ChunkedNioFile(channel, entryValue.offset, entryValue.size - entryValue.offset, chunkSize))
                    ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                }
            }
        } ?: ctx.writeAndFlush(CacheValueNotFoundResponse())
    }

    private fun handlePutRequest(ctx: ChannelHandlerContext, msg: CachePutRequest) {
        val key = String(Base64.getUrlEncoder().encode(processCacheKey(msg.key, digestAlgorithm)))
        val sink = cache.put(key, msg.metadata)
        inProgressPutRequest = InProgressPutRequest(msg.key, sink)
    }

    private fun handleCacheContent(ctx: ChannelHandlerContext, msg: CacheContent) {
        inProgressPutRequest!!.write(msg.content())
    }

    private fun handleLastCacheContent(ctx: ChannelHandlerContext, msg: LastCacheContent) {
        inProgressPutRequest?.let { request ->
            inProgressPutRequest = null
            request.write(msg.content())
            request.commit()
            ctx.writeAndFlush(CachePutResponse(request.key))
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        inProgressPutRequest?.rollback()
        super.exceptionCaught(ctx, cause)
    }
}