package net.woggioni.rbcs.server.cache

import io.netty.buffer.ByteBuf
import net.woggioni.rbcs.api.AsyncCloseable
import net.woggioni.rbcs.api.CacheValueMetadata
import net.woggioni.rbcs.common.createLogger
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private class CacheKey(private val value: ByteArray) {
    override fun equals(other: Any?) = if (other is CacheKey) {
        value.contentEquals(other.value)
    } else false

    override fun hashCode() = value.contentHashCode()
}

class CacheEntry(
    val metadata: CacheValueMetadata,
    val content: ByteBuf
)

class InMemoryCache(
    private val maxAge: Duration,
    private val maxSize: Long
) : AsyncCloseable {

    companion object {
        private val log = createLogger<InMemoryCache>()
    }

    private val size = AtomicLong()
    private val map = ConcurrentHashMap<CacheKey, CacheEntry>()

    private class RemovalQueueElement(val key: CacheKey, val value: CacheEntry, val expiry: Instant) :
        Comparable<RemovalQueueElement> {
        override fun compareTo(other: RemovalQueueElement) = expiry.compareTo(other.expiry)
    }

    private val removalQueue = PriorityBlockingQueue<RemovalQueueElement>()

    @Volatile
    private var running = true

    private val closeFuture = object : CompletableFuture<Void>() {
        init {
            Thread.ofVirtual().name("in-memory-cache-gc").start {
                try {
                    while (running) {
                        val el = removalQueue.poll(1, TimeUnit.SECONDS) ?: continue
                        val value = el.value
                        val now = Instant.now()
                        if (now > el.expiry) {
                            val removed = map.remove(el.key, value)
                            if (removed) {
                                updateSizeAfterRemoval(value.content)
                                //Decrease the reference count for map
                                value.content.release()
                            }
                        } else {
                            removalQueue.put(el)
                            Thread.sleep(minOf(Duration.between(now, el.expiry), Duration.ofSeconds(1)))
                        }
                    }
                    complete(null)
                } catch (ex: Throwable) {
                    completeExceptionally(ex)
                }
            }
        }
    }

    fun removeEldest(): Long {
        while (true) {
            val el = removalQueue.take()
            val value = el.value
            val removed = map.remove(el.key, value)
            if (removed) {
                val newSize = updateSizeAfterRemoval(value.content)
                //Decrease the reference count for map
                value.content.release()
                return newSize
            }
        }
    }

    private fun updateSizeAfterRemoval(removed: ByteBuf): Long {
        return size.updateAndGet { currentSize: Long ->
            currentSize - removed.readableBytes()
        }
    }

    override fun asyncClose() : CompletableFuture<Void> {
        running = false
        return closeFuture
    }

    fun get(key: ByteArray) = map[CacheKey(key)]?.run {
        CacheEntry(metadata, content.retainedDuplicate())
    }

    fun put(
        key: ByteArray,
        value: CacheEntry,
    ) {
        val cacheKey = CacheKey(key)
        val oldSize = map.put(cacheKey, value)?.let { old ->
            val result = old.content.readableBytes()
            old.content.release()
            result
        } ?: 0
        val delta = value.content.readableBytes() - oldSize
        var newSize = size.updateAndGet { currentSize: Long ->
            currentSize + delta
        }
        removalQueue.put(RemovalQueueElement(cacheKey, value, Instant.now().plus(maxAge)))
        while (newSize > maxSize) {
            newSize = removeEldest()
        }
    }
}