package net.woggioni.gbcs.server.cache

import io.netty.buffer.ByteBuf
import net.woggioni.gbcs.api.Cache
import net.woggioni.gbcs.common.ByteBufInputStream
import net.woggioni.gbcs.common.ByteBufOutputStream
import net.woggioni.gbcs.common.GBCS.digestString
import net.woggioni.gbcs.common.contextLogger
import net.woggioni.jwo.JWO
import java.nio.channels.Channels
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

class InMemoryCache(
    val maxAge: Duration,
    val maxSize: Long,
    val digestAlgorithm: String?,
    val compressionEnabled: Boolean,
    val compressionLevel: Int
) : Cache {

    companion object {
        @JvmStatic
        private val log = contextLogger()
    }

    private val size = AtomicLong()
    private val map = ConcurrentHashMap<String, ByteBuf>()
    
    private class RemovalQueueElement(val key: String, val value : ByteBuf, val expiry : Instant) : Comparable<RemovalQueueElement> {
        override fun compareTo(other: RemovalQueueElement) = expiry.compareTo(other.expiry)
    }

    private val removalQueue = PriorityBlockingQueue<RemovalQueueElement>()

    private var running = true
    private val garbageCollector = Thread {
        while(true) {
            val el = removalQueue.take()
            val buf = el.value
            val now = Instant.now()
            if(now > el.expiry) {
                val removed = map.remove(el.key, buf)
                if(removed) {
                    updateSizeAfterRemoval(buf)
                    //Decrease the reference count for map
                    buf.release()
                }
                //Decrease the reference count for removalQueue
                buf.release()
            } else {
                removalQueue.put(el)
                Thread.sleep(minOf(Duration.between(now, el.expiry), Duration.ofSeconds(1)))
            }
        }
    }.apply {
        start()
    }

    private fun removeEldest() : Long {
        while(true) {
            val el = removalQueue.take()
            val buf = el.value
            val removed = map.remove(el.key, buf)
            //Decrease the reference count for removalQueue
            buf.release()
            if(removed) {
                val newSize = updateSizeAfterRemoval(buf)
                //Decrease the reference count for map
                buf.release()
                return newSize
            }
        }
    }

    private fun updateSizeAfterRemoval(removed: ByteBuf) : Long {
        return size.updateAndGet { currentSize : Long ->
            currentSize - removed.readableBytes()
        }
    }

    override fun close() {
        running = false
        garbageCollector.join()
    }

    override fun get(key: String) =
        (digestAlgorithm
            ?.let(MessageDigest::getInstance)
            ?.let { md ->
                digestString(key.toByteArray(), md)
            } ?: key
                ).let { digest ->
                map[digest]
                    ?.let { value ->
                    val copy = value.retainedDuplicate()
                    copy.touch("This has to be released by the caller of the cache")
                    if (compressionEnabled) {
                        val inflater = Inflater()
                        Channels.newChannel(InflaterInputStream(ByteBufInputStream(copy), inflater))
                    } else {
                        Channels.newChannel(ByteBufInputStream(copy))
                    }
                }
            }.let {
                CompletableFuture.completedFuture(it)
            }

    override fun put(key: String, content: ByteBuf) =
        (digestAlgorithm
            ?.let(MessageDigest::getInstance)
            ?.let { md ->
                digestString(key.toByteArray(), md)
            } ?: key).let { digest ->
            content.retain()
            val value = if (compressionEnabled) {
                val deflater = Deflater(compressionLevel)
                val buf = content.alloc().buffer()
                buf.retain()
                DeflaterOutputStream(ByteBufOutputStream(buf), deflater).use { outputStream ->
                    ByteBufInputStream(content).use { inputStream ->
                        JWO.copy(inputStream, outputStream)
                    }
                }
                buf
            } else {
                content
            }
            val old = map.put(digest, value)
            val delta = value.readableBytes() - (old?.readableBytes() ?: 0)
            var newSize = size.updateAndGet { currentSize : Long ->
                currentSize + delta
            }
            removalQueue.put(RemovalQueueElement(digest, value.retain(), Instant.now().plus(maxAge)))
            while(newSize > maxSize) {
                newSize = removeEldest()
            }
        }.let {
            CompletableFuture.completedFuture<Void>(null)
        }
}