package net.woggioni.rbcs.server.cache

import java.time.Duration
import java.time.Instant
import java.util.PriorityQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock
import net.woggioni.rbcs.api.AsyncCloseable
import net.woggioni.rbcs.api.CacheValueMetadata
import net.woggioni.rbcs.common.createLogger

private class CacheKey(private val value: ByteArray) {
    override fun equals(other: Any?) = if (other is CacheKey) {
        value.contentEquals(other.value)
    } else false

    override fun hashCode() = value.contentHashCode()
}

class CacheEntry(
    val metadata: CacheValueMetadata,
    val content: ByteArray
)

class InMemoryCache(
    private val maxAge: Duration,
    private val maxSize: Long
) : AsyncCloseable {

    companion object {
        private val log = createLogger<InMemoryCache>()
    }

    private var mapSize : Long = 0
    private val map = HashMap<CacheKey, CacheEntry>()
    private val lock = ReentrantReadWriteLock()
    private val cond = lock.writeLock().newCondition()

    private class RemovalQueueElement(val key: CacheKey, val value: CacheEntry, val expiry: Instant) :
        Comparable<RemovalQueueElement> {
        override fun compareTo(other: RemovalQueueElement) = expiry.compareTo(other.expiry)
    }

    private val removalQueue = PriorityQueue<RemovalQueueElement>()

    @Volatile
    private var running = true

    private val closeFuture = object : CompletableFuture<Void>() {
        init {
            Thread.ofVirtual().name("in-memory-cache-gc").start {
                try {
                    lock.writeLock().withLock {
                        while (running) {
                            val el = removalQueue.poll()
                            if(el == null) {
                                cond.await(1000, TimeUnit.MILLISECONDS)
                                continue
                            }
                            val value = el.value
                            val now = Instant.now()
                            if (now > el.expiry) {
                                val removed = map.remove(el.key, value)
                                if (removed) {
                                    updateSizeAfterRemoval(value.content)
                                }
                            } else {
                                removalQueue.offer(el)
                                val interval = minOf(Duration.between(now, el.expiry), Duration.ofSeconds(1))
                                cond.await(interval.toMillis(), TimeUnit.MILLISECONDS)
                            }
                        }
                        map.clear()
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
            val el = removalQueue.poll() ?: return mapSize
            val value = el.value
            val removed = map.remove(el.key, value)
            if (removed) {
                val newSize = updateSizeAfterRemoval(value.content)
                return newSize
            }
        }
    }

    private fun updateSizeAfterRemoval(removed: ByteArray): Long {
        mapSize -= removed.size
        return mapSize
    }

    override fun asyncClose() : CompletableFuture<Void> {
        running = false
        lock.writeLock().withLock {
            cond.signal()
        }
        return closeFuture
    }

    fun get(key: ByteArray) = lock.readLock().withLock {
        map[CacheKey(key)]?.run {
            CacheEntry(metadata, content)
        }
    }

    fun put(
        key: ByteArray,
        value: CacheEntry,
    ) {
        val cacheKey = CacheKey(key)
        lock.writeLock().withLock {
            val oldSize = map.put(cacheKey, value)?.content?.size ?: 0
            val delta = value.content.size - oldSize
            mapSize += delta
            removalQueue.offer(RemovalQueueElement(cacheKey, value, Instant.now().plus(maxAge)))
            while (mapSize > maxSize) {
                removeEldest()
            }
        }
    }
}