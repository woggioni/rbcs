package net.woggioni.rbcs.server.cache

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import net.woggioni.rbcs.api.Cache
import net.woggioni.rbcs.api.RequestHandle
import net.woggioni.rbcs.api.ResponseHandle
import net.woggioni.rbcs.api.event.RequestStreamingEvent
import net.woggioni.rbcs.api.event.ResponseStreamingEvent
import net.woggioni.rbcs.common.ByteBufOutputStream
import net.woggioni.rbcs.common.RBCS.digestString
import net.woggioni.rbcs.common.contextLogger
import net.woggioni.rbcs.common.extractChunk
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterOutputStream

class InMemoryCache(
    private val maxAge: Duration,
    private val maxSize: Long,
    private val digestAlgorithm: String?,
    private val compressionEnabled: Boolean,
    private val compressionLevel: Int,
    private val chunkSize : Int
) : Cache {

    companion object {
        @JvmStatic
        private val log = contextLogger()
    }

    private val size = AtomicLong()
    private val map = ConcurrentHashMap<String, ByteBuf>()

    private class RemovalQueueElement(val key: String, val value: ByteBuf, val expiry: Instant) :
        Comparable<RemovalQueueElement> {
        override fun compareTo(other: RemovalQueueElement) = expiry.compareTo(other.expiry)
    }

    private val removalQueue = PriorityBlockingQueue<RemovalQueueElement>()

    @Volatile
    private var running = true

    private val garbageCollector = Thread.ofVirtual().name("in-memory-cache-gc").start {
        while (running) {
            val el = removalQueue.poll(1, TimeUnit.SECONDS) ?: continue
            val buf = el.value
            val now = Instant.now()
            if (now > el.expiry) {
                val removed = map.remove(el.key, buf)
                if (removed) {
                    updateSizeAfterRemoval(buf)
                    //Decrease the reference count for map
                    buf.release()
                }
            } else {
                removalQueue.put(el)
                Thread.sleep(minOf(Duration.between(now, el.expiry), Duration.ofSeconds(1)))
            }
        }
    }

    private fun removeEldest(): Long {
        while (true) {
            val el = removalQueue.take()
            val buf = el.value
            val removed = map.remove(el.key, buf)
            if (removed) {
                val newSize = updateSizeAfterRemoval(buf)
                //Decrease the reference count for map
                buf.release()
                return newSize
            }
        }
    }

    private fun updateSizeAfterRemoval(removed: ByteBuf): Long {
        return size.updateAndGet { currentSize: Long ->
            currentSize - removed.readableBytes()
        }
    }

    override fun close() {
        running = false
        garbageCollector.join()
    }

    override fun get(key: String, responseHandle: ResponseHandle, alloc: ByteBufAllocator) {
        try {
            (digestAlgorithm
                ?.let(MessageDigest::getInstance)
                ?.let { md ->
                    digestString(key.toByteArray(), md)
                } ?: key
                    ).let { digest ->
                    map[digest]
                        ?.let { value ->
                            val copy = value.retainedDuplicate()
                            responseHandle.handleEvent(ResponseStreamingEvent.RESPONSE_RECEIVED)
                            val output = alloc.compositeBuffer()
                            if (compressionEnabled) {
                                try {
                                    val stream = ByteBufOutputStream(output).let {
                                        val inflater = Inflater()
                                        InflaterOutputStream(it, inflater)
                                    }
                                    stream.use { os ->
                                        var readable = copy.readableBytes()
                                        while (true) {
                                            copy.readBytes(os, chunkSize.coerceAtMost(readable))
                                            readable = copy.readableBytes()
                                            val last = readable == 0
                                            if (last) stream.flush()
                                            if (output.readableBytes() >= chunkSize || last) {
                                                val chunk = extractChunk(output, alloc)
                                                val evt = if (last) {
                                                    ResponseStreamingEvent.LastChunkReceived(chunk)
                                                } else {
                                                    ResponseStreamingEvent.ChunkReceived(chunk)
                                                }
                                                responseHandle.handleEvent(evt)
                                            }
                                            if (last) break
                                        }
                                    }
                                } finally {
                                    copy.release()
                                }
                            } else {
                                responseHandle.handleEvent(
                                    ResponseStreamingEvent.LastChunkReceived(copy)
                                )
                            }
                        } ?: responseHandle.handleEvent(ResponseStreamingEvent.NOT_FOUND)
                }
        } catch (ex: Throwable) {
            responseHandle.handleEvent(ResponseStreamingEvent.ExceptionCaught(ex))
        }
    }

    override fun put(
        key: String,
        responseHandle: ResponseHandle,
        alloc: ByteBufAllocator
    ): CompletableFuture<RequestHandle> {
        return CompletableFuture.completedFuture(object : RequestHandle {
            val buf = alloc.heapBuffer()
            val stream = ByteBufOutputStream(buf).let {
                if (compressionEnabled) {
                    val deflater = Deflater(compressionLevel)
                    DeflaterOutputStream(it, deflater)
                } else {
                    it
                }
            }

            override fun handleEvent(evt: RequestStreamingEvent) {
                when (evt) {
                    is RequestStreamingEvent.ChunkReceived -> {
                        evt.chunk.readBytes(stream, evt.chunk.readableBytes())
                        if (evt is RequestStreamingEvent.LastChunkReceived) {
                            (digestAlgorithm
                                ?.let(MessageDigest::getInstance)
                                ?.let { md ->
                                    digestString(key.toByteArray(), md)
                                } ?: key
                                    ).let { digest ->
                                    val oldSize = map.put(digest, buf.retain())?.let { old ->
                                        val result = old.readableBytes()
                                        old.release()
                                        result
                                    } ?: 0
                                    val delta = buf.readableBytes() - oldSize
                                    var newSize = size.updateAndGet { currentSize : Long ->
                                        currentSize + delta
                                    }
                                    removalQueue.put(RemovalQueueElement(digest, buf, Instant.now().plus(maxAge)))
                                    while(newSize > maxSize) {
                                        newSize = removeEldest()
                                    }
                                    stream.close()
                                    responseHandle.handleEvent(ResponseStreamingEvent.RESPONSE_RECEIVED)
                                }
                        }
                    }

                    is RequestStreamingEvent.ExceptionCaught -> {
                        stream.close()
                    }

                    else -> {

                    }
                }
            }
        })
    }
}