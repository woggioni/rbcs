package net.woggioni.gbcs.server.cache

import net.woggioni.gbcs.api.Cache
import net.woggioni.gbcs.server.cache.CacheUtils.digestString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

class InMemoryCache(
    val maxAge: Duration,
    val digestAlgorithm: String?,
    val compressionEnabled: Boolean,
    val compressionLevel: Int
) : Cache {

    private val map = ConcurrentHashMap<String, MapValue>()

    private class MapValue(val rc: AtomicInteger, val payload : AtomicReference<ByteArray>)

    private class RemovalQueueElement(val key: String, val expiry : Instant) : Comparable<RemovalQueueElement> {
        override fun compareTo(other: RemovalQueueElement)= expiry.compareTo(other.expiry)
    }

    private val removalQueue = PriorityBlockingQueue<RemovalQueueElement>()

    private var running = true
    private val garbageCollector = Thread({
        while(true) {
            val el = removalQueue.take()
            val now = Instant.now()
            if(now > el.expiry) {
                val value = map[el.key] ?: continue
                val rc = value.rc.decrementAndGet()
                if(rc == 0) {
                    map.remove(el.key)
                }
            } else {
                removalQueue.put(el)
                Thread.sleep(minOf(Duration.between(now, el.expiry), Duration.ofSeconds(1)))
            }
        }
    }).apply {
        start()
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
                    ?.let(MapValue::payload)
                    ?.let(AtomicReference<ByteArray>::get)
                    ?.let { value ->
                    if (compressionEnabled) {
                        val inflater = Inflater()
                        Channels.newChannel(InflaterInputStream(ByteArrayInputStream(value), inflater))
                    } else {
                        Channels.newChannel(ByteArrayInputStream(value))
                    }
                }
            }

    override fun put(key: String, content: ByteArray) {
        (digestAlgorithm
            ?.let(MessageDigest::getInstance)
            ?.let { md ->
                digestString(key.toByteArray(), md)
            } ?: key).let { digest ->
            val value = if (compressionEnabled) {
                val deflater = Deflater(compressionLevel)
                val baos = ByteArrayOutputStream()
                DeflaterOutputStream(baos, deflater).use { stream ->
                    stream.write(content)
                }
                baos.toByteArray()
            } else {
                content
            }
            val mapValue = map.computeIfAbsent(digest) {
                MapValue(AtomicInteger(0), AtomicReference())
            }
            mapValue.payload.set(value)
            removalQueue.put(RemovalQueueElement(digest, Instant.now().plus(maxAge)))
        }
    }
}