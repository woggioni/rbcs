package net.woggioni.rbcs.servlet

import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import net.woggioni.jwo.HttpClient.HttpStatus
import net.woggioni.jwo.JWO


private class CacheKey(private val value: ByteArray) {
    override fun equals(other: Any?) = if (other is CacheKey) {
        value.contentEquals(other.value)
    } else false

    override fun hashCode() = value.contentHashCode()
}


@ApplicationScoped
open class InMemoryServletCache : AutoCloseable {

    private val maxAge= Duration.ofDays(7)
    private val maxSize = 0x8000000

    companion object {
        @JvmStatic
        private val log = Logger.getLogger(this::class.java.name)
    }

    private val size = AtomicLong()
    private val map = ConcurrentHashMap<CacheKey, ByteArray>()

    private class RemovalQueueElement(val key: CacheKey, val value: ByteArray, val expiry: Instant) :
        Comparable<RemovalQueueElement> {
        override fun compareTo(other: RemovalQueueElement) = expiry.compareTo(other.expiry)
    }

    private val removalQueue = PriorityBlockingQueue<RemovalQueueElement>()

    @Volatile
    private var running = false

    private val garbageCollector = Thread.ofVirtual().name("in-memory-cache-gc").start {
        while (running) {
            val el = removalQueue.poll(1, TimeUnit.SECONDS) ?: continue
            val value = el.value
            val now = Instant.now()
            if (now > el.expiry) {
                val removed = map.remove(el.key, value)
                if (removed) {
                    updateSizeAfterRemoval(value)
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
            val value = el.value
            val removed = map.remove(el.key, value)
            if (removed) {
                val newSize = updateSizeAfterRemoval(value)
                return newSize
            }
        }
    }

    private fun updateSizeAfterRemoval(removed: ByteArray): Long {
        return size.updateAndGet { currentSize: Long ->
            currentSize - removed.size
        }
    }

    @PreDestroy
    override fun close() {
        running = false
        garbageCollector.join()
    }

    open fun get(key: ByteArray) = map[CacheKey(key)]

    open fun put(
        key: ByteArray,
        value: ByteArray,
    ) {
        val cacheKey = CacheKey(key)
        val oldSize = map.put(cacheKey, value)?.let { old ->
            val result = old.size
            result
        } ?: 0
        val delta = value.size - oldSize
        var newSize = size.updateAndGet { currentSize: Long ->
            currentSize + delta
        }
        removalQueue.put(RemovalQueueElement(cacheKey, value, Instant.now().plus(maxAge)))
        while (newSize > maxSize) {
            newSize = removeEldest()
        }
    }
}


@WebServlet(urlPatterns = ["/cache/*"])
class CacheServlet : HttpServlet() {
    companion object {
        @JvmStatic
        private val log = Logger.getLogger(this::class.java.name)
    }

    @Inject
    private lateinit var cache : InMemoryServletCache

    private fun getKey(req : HttpServletRequest) : String {
        return Path.of(req.pathInfo).fileName.toString()
    }

    override fun doPut(req: HttpServletRequest, resp: HttpServletResponse) {
        val baos = ByteArrayOutputStream()
        baos.use {
            JWO.copy(req.inputStream, baos)
        }
        val key = getKey(req)
        cache.put(key.toByteArray(Charsets.UTF_8), baos.toByteArray())
        resp.status = 201
        resp.setContentLength(0)
        log.fine {
            "[${Thread.currentThread().name}] Added value for key $key"
        }
    }

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val key = getKey(req)
        val value = cache.get(key.toByteArray(Charsets.UTF_8))
        if (value == null) {
            log.fine {
                "[${Thread.currentThread().name}] Cache miss for key $key"
            }
            resp.status = HttpStatus.NOT_FOUND.code
            resp.setContentLength(0)
        } else {
            log.fine {
                "[${Thread.currentThread().name}] Cache hit for key $key"
            }
            resp.status = HttpStatus.OK.code
            resp.setContentLength(value.size)
            ByteArrayInputStream(value).use {
                JWO.copy(it, resp.outputStream)
            }
        }
    }
}