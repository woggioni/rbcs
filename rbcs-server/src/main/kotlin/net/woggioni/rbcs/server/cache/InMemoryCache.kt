package net.woggioni.rbcs.server.cache

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.netty.buffer.ByteBuf
import net.woggioni.rbcs.api.AsyncCloseable
import net.woggioni.rbcs.api.CacheValueMetadata
import net.woggioni.rbcs.common.createLogger
import java.time.Duration
import java.util.concurrent.CompletableFuture

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
    maxAge: Duration,
    maxSize: Long
) : AsyncCloseable {

    companion object {
        private val log = createLogger<InMemoryCache>()
    }

    private val cache: Cache<CacheKey, CacheEntry> = Caffeine.newBuilder()
        .expireAfterWrite(maxAge)
        .maximumSize(maxSize)
        .build()
    override fun asyncClose(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    fun get(key: ByteArray) = cache.getIfPresent(CacheKey(key))?.run {
            CacheEntry(metadata, content.retainedDuplicate())
        }

    fun put(
        key: ByteArray,
        value: CacheEntry,
    ) {
        val cacheKey = CacheKey(key)
        cache.put(cacheKey, value)
    }
}