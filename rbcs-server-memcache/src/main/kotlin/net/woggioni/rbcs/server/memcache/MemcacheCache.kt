package net.woggioni.rbcs.server.memcache

import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.handler.codec.memcache.binary.BinaryMemcacheOpcodes
import io.netty.handler.codec.memcache.binary.BinaryMemcacheResponseStatus
import io.netty.handler.codec.memcache.binary.DefaultBinaryMemcacheRequest
import net.woggioni.rbcs.api.Cache
import net.woggioni.rbcs.api.RequestHandle
import net.woggioni.rbcs.api.ResponseHandle
import net.woggioni.rbcs.api.event.RequestStreamingEvent
import net.woggioni.rbcs.api.event.ResponseStreamingEvent
import net.woggioni.rbcs.api.exception.ContentTooLargeException
import net.woggioni.rbcs.common.ByteBufOutputStream
import net.woggioni.rbcs.common.RBCS.digest
import net.woggioni.rbcs.common.contextLogger
import net.woggioni.rbcs.common.debug
import net.woggioni.rbcs.common.extractChunk
import net.woggioni.rbcs.server.memcache.client.MemcacheClient
import net.woggioni.rbcs.server.memcache.client.MemcacheResponseHandle
import net.woggioni.rbcs.server.memcache.client.StreamingRequestEvent
import net.woggioni.rbcs.server.memcache.client.StreamingResponseEvent
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterOutputStream

class MemcacheCache(private val cfg: MemcacheCacheConfiguration) : Cache {

    companion object {
        @JvmStatic
        private val log = contextLogger()
    }

    private val memcacheClient = MemcacheClient(cfg)

    override fun get(key: String, responseHandle: ResponseHandle, alloc: ByteBufAllocator) {
        val compressionMode = cfg.compressionMode
        val buf = alloc.compositeBuffer()
        val stream = ByteBufOutputStream(buf).let { outputStream ->
            if (compressionMode != null) {
                when (compressionMode) {
                    MemcacheCacheConfiguration.CompressionMode.DEFLATE -> {
                        InflaterOutputStream(
                            outputStream,
                            Inflater()
                        )
                    }
                }
            } else {
                outputStream
            }
        }
        val memcacheResponseHandle = object : MemcacheResponseHandle {
            override fun handleEvent(evt: StreamingResponseEvent) {
                when (evt) {
                    is StreamingResponseEvent.ResponseReceived -> {
                        if (evt.response.status() == BinaryMemcacheResponseStatus.SUCCESS) {
                            responseHandle.handleEvent(ResponseStreamingEvent.RESPONSE_RECEIVED)
                        } else if (evt.response.status() == BinaryMemcacheResponseStatus.KEY_ENOENT) {
                            responseHandle.handleEvent(ResponseStreamingEvent.NOT_FOUND)
                        } else {
                            responseHandle.handleEvent(ResponseStreamingEvent.ExceptionCaught(MemcacheException(evt.response.status())))
                        }
                    }

                    is StreamingResponseEvent.LastContentReceived -> {
                        evt.content.content().let { content ->
                            content.readBytes(stream, content.readableBytes())
                        }
                        buf.retain()
                        stream.close()
                        val chunk = extractChunk(buf, alloc)
                        buf.release()
                        responseHandle.handleEvent(
                            ResponseStreamingEvent.LastChunkReceived(
                                chunk
                            )
                        )
                    }

                    is StreamingResponseEvent.ContentReceived -> {
                        evt.content.content().let { content ->
                            content.readBytes(stream, content.readableBytes())
                        }
                        if (buf.readableBytes() >= cfg.chunkSize) {
                            val chunk = extractChunk(buf, alloc)
                            responseHandle.handleEvent(ResponseStreamingEvent.ChunkReceived(chunk))
                        }
                    }

                    is StreamingResponseEvent.ExceptionCaught -> {
                        responseHandle.handleEvent(ResponseStreamingEvent.ExceptionCaught(evt.exception))
                    }
                }
            }
        }
        memcacheClient.sendRequest(Unpooled.wrappedBuffer(key.toByteArray()), memcacheResponseHandle)
            .thenApply { memcacheRequestHandle ->
                val request = (cfg.digestAlgorithm
                    ?.let(MessageDigest::getInstance)
                    ?.let { md ->
                        digest(key.toByteArray(), md)
                    } ?: key.toByteArray(Charsets.UTF_8)
                        ).let { digest ->
                        DefaultBinaryMemcacheRequest(Unpooled.wrappedBuffer(digest)).apply {
                            setOpcode(BinaryMemcacheOpcodes.GET)
                        }
                    }
                memcacheRequestHandle.handleEvent(StreamingRequestEvent.SendRequest(request))
            }.exceptionally { ex ->
                responseHandle.handleEvent(ResponseStreamingEvent.ExceptionCaught(ex))
            }
    }

    private fun encodeExpiry(expiry: Duration): Int {
        val expirySeconds = expiry.toSeconds()
        return expirySeconds.toInt().takeIf { it.toLong() == expirySeconds }
            ?: Instant.ofEpochSecond(expirySeconds).epochSecond.toInt()
    }

    override fun put(
        key: String,
        responseHandle: ResponseHandle,
        alloc: ByteBufAllocator
    ): CompletableFuture<RequestHandle> {
        val memcacheResponseHandle = object : MemcacheResponseHandle {
            override fun handleEvent(evt: StreamingResponseEvent) {
                when (evt) {
                    is StreamingResponseEvent.ResponseReceived -> {
                        when (evt.response.status()) {
                            BinaryMemcacheResponseStatus.SUCCESS -> {
                                responseHandle.handleEvent(ResponseStreamingEvent.RESPONSE_RECEIVED)
                            }

                            BinaryMemcacheResponseStatus.KEY_ENOENT -> {
                                responseHandle.handleEvent(ResponseStreamingEvent.NOT_FOUND)
                            }

                            BinaryMemcacheResponseStatus.E2BIG -> {
                                responseHandle.handleEvent(
                                    ResponseStreamingEvent.ExceptionCaught(
                                        ContentTooLargeException("Request payload is too big", null)
                                    )
                                )
                            }

                            else -> {
                                responseHandle.handleEvent(ResponseStreamingEvent.ExceptionCaught(MemcacheException(evt.response.status())))
                            }
                        }
                    }

                    is StreamingResponseEvent.LastContentReceived -> {
                        responseHandle.handleEvent(
                            ResponseStreamingEvent.LastChunkReceived(
                                evt.content.content().retain()
                            )
                        )
                    }

                    is StreamingResponseEvent.ContentReceived -> {
                        responseHandle.handleEvent(ResponseStreamingEvent.ChunkReceived(evt.content.content().retain()))
                    }

                    is StreamingResponseEvent.ExceptionCaught -> {
                        responseHandle.handleEvent(ResponseStreamingEvent.ExceptionCaught(evt.exception))
                    }
                }
            }
        }
        val result: CompletableFuture<RequestHandle> =
            memcacheClient.sendRequest(Unpooled.wrappedBuffer(key.toByteArray()), memcacheResponseHandle)
                .thenApply { memcacheRequestHandle ->
                    val request = (cfg.digestAlgorithm
                        ?.let(MessageDigest::getInstance)
                        ?.let { md ->
                            digest(key.toByteArray(), md)
                        } ?: key.toByteArray(Charsets.UTF_8)).let { digest ->
                        val extras = Unpooled.buffer(8, 8)
                        extras.writeInt(0)
                        extras.writeInt(encodeExpiry(cfg.maxAge))
                        DefaultBinaryMemcacheRequest(Unpooled.wrappedBuffer(digest), extras).apply {
                            setOpcode(BinaryMemcacheOpcodes.SET)
                        }
                    }
//                    memcacheRequestHandle.handleEvent(StreamingRequestEvent.SendRequest(request))
                    val compressionMode = cfg.compressionMode
                    val buf = alloc.heapBuffer()
                    val stream = ByteBufOutputStream(buf).let { outputStream ->
                        if (compressionMode != null) {
                            when (compressionMode) {
                                MemcacheCacheConfiguration.CompressionMode.DEFLATE -> {
                                    DeflaterOutputStream(
                                        outputStream,
                                        Deflater(Deflater.DEFAULT_COMPRESSION, false)
                                    )
                                }
                            }
                        } else {
                            outputStream
                        }
                    }
                    RequestHandle { evt ->
                        when (evt) {
                            is RequestStreamingEvent.LastChunkReceived -> {
                                evt.chunk.readBytes(stream, evt.chunk.readableBytes())
                                buf.retain()
                                stream.close()
                                request.setTotalBodyLength(buf.readableBytes() + request.keyLength() + request.extrasLength())
                                memcacheRequestHandle.handleEvent(StreamingRequestEvent.SendRequest(request))
                                memcacheRequestHandle.handleEvent(StreamingRequestEvent.SendLastChunk(buf))
                            }

                            is RequestStreamingEvent.ChunkReceived -> {
                                evt.chunk.readBytes(stream, evt.chunk.readableBytes())
                            }

                            is RequestStreamingEvent.ExceptionCaught -> {
                                stream.close()
                            }
                        }
                    }
                }
        return result
    }

    override fun close() {
        memcacheClient.close()
    }
}
