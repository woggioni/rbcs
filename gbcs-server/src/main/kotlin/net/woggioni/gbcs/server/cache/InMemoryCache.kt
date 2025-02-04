package net.woggioni.gbcs.server.cache

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.woggioni.gbcs.api.Cache
import net.woggioni.gbcs.common.ByteBufInputStream
import net.woggioni.gbcs.common.GBCS.digestString
import net.woggioni.jwo.JWO
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
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

    private val map = ConcurrentHashMap<String, ByteBuf>()
    
    private class RemovalQueueElement(val key: String, val value : ByteBuf, val expiry : Instant) : Comparable<RemovalQueueElement> {
        override fun compareTo(other: RemovalQueueElement) = expiry.compareTo(other.expiry)
    }

    private val removalQueue = PriorityBlockingQueue<RemovalQueueElement>()

    private var running = true
    private val garbageCollector = Thread {
        while(true) {
            val el = removalQueue.take()
            val now = Instant.now()
            if(now > el.expiry) {
                map.remove(el.key, el.value)
            } else {
                removalQueue.put(el)
                Thread.sleep(minOf(Duration.between(now, el.expiry), Duration.ofSeconds(1)))
            }
        }
    }.apply {
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
                    ?.let { value ->
                    if (compressionEnabled) {
                        val inflater = Inflater()
                        Channels.newChannel(InflaterInputStream(ByteBufInputStream(value), inflater))
                    } else {
                        Channels.newChannel(ByteBufInputStream(value))
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
            val value = if (compressionEnabled) {
                val deflater = Deflater(compressionLevel)
                val baos = ByteArrayOutputStream()
                DeflaterOutputStream(baos, deflater).use { stream ->
                    JWO.copy(ByteBufInputStream(content), stream)
                }
                Unpooled.wrappedBuffer(baos.toByteArray())
            } else {
                content
            }
            map[digest] = value
            removalQueue.put(RemovalQueueElement(digest, value, Instant.now().plus(maxAge)))
        }.let {
            CompletableFuture.completedFuture<Void>(null)
        }
}