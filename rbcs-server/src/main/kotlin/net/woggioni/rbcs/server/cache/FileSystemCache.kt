package net.woggioni.rbcs.server.cache

import io.netty.buffer.ByteBuf
import net.woggioni.jwo.JWO
import net.woggioni.rbcs.api.Cache
import net.woggioni.rbcs.common.ByteBufInputStream
import net.woggioni.rbcs.common.RBCS.digestString
import net.woggioni.rbcs.common.contextLogger
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

class FileSystemCache(
    val root: Path,
    val maxAge: Duration,
    val digestAlgorithm: String?,
    val compressionEnabled: Boolean,
    val compressionLevel: Int
) : Cache {

    private companion object {
        @JvmStatic
        private val log = contextLogger()
    }

    init {
        Files.createDirectories(root)
    }

    @Volatile
    private var running = true

    private var nextGc = Instant.now()

    override fun get(key: String) = (digestAlgorithm
        ?.let(MessageDigest::getInstance)
        ?.let { md ->
            digestString(key.toByteArray(), md)
        } ?: key).let { digest ->
        root.resolve(digest).takeIf(Files::exists)
            ?.let { file ->
                file.takeIf(Files::exists)?.let { file ->
                    if (compressionEnabled) {
                        val inflater = Inflater()
                        Channels.newChannel(
                            InflaterInputStream(
                                Channels.newInputStream(
                                    FileChannel.open(
                                        file,
                                        StandardOpenOption.READ
                                    )
                                ), inflater
                            )
                        )
                    } else {
                        FileChannel.open(file, StandardOpenOption.READ)
                    }
                }
            }.let {
                CompletableFuture.completedFuture(it)
            }
    }

    override fun put(key: String, content: ByteBuf): CompletableFuture<Void> {
        (digestAlgorithm
            ?.let(MessageDigest::getInstance)
            ?.let { md ->
                digestString(key.toByteArray(), md)
            } ?: key).let { digest ->
            val file = root.resolve(digest)
            val tmpFile = Files.createTempFile(root, null, ".tmp")
            try {
                Files.newOutputStream(tmpFile).let {
                    if (compressionEnabled) {
                        val deflater = Deflater(compressionLevel)
                        DeflaterOutputStream(it, deflater)
                    } else {
                        it
                    }
                }.use {
                    JWO.copy(ByteBufInputStream(content), it)
                }
                Files.move(tmpFile, file, StandardCopyOption.ATOMIC_MOVE)
            } catch (t: Throwable) {
                Files.delete(tmpFile)
                throw t
            }
        }
        return CompletableFuture.completedFuture(null)
    }

    private val garbageCollector = Thread.ofVirtual().name("file-system-cache-gc").start {
        while (running) {
            gc()
        }
    }

    private fun gc() {
        val now = Instant.now()
        if (nextGc < now) {
            val oldestEntry = actualGc(now)
            nextGc = (oldestEntry ?: now).plus(maxAge)
        }
        Thread.sleep(minOf(Duration.between(now, nextGc), Duration.ofSeconds(1)))
    }

    /**
     * Returns the creation timestamp of the oldest cache entry (if any)
     */
    private fun actualGc(now: Instant) : Instant? {
        var result :Instant? = null
        Files.list(root)
            .filter { path ->
                JWO.splitExtension(path)
                    .map { it._2 }
                    .map { it != ".tmp" }
                    .orElse(true)
            }
            .filter {
                val creationTimeStamp = Files.readAttributes(it, BasicFileAttributes::class.java)
                    .creationTime()
                    .toInstant()
                if(result == null || creationTimeStamp < result) {
                    result = creationTimeStamp
                }
                now > creationTimeStamp.plus(maxAge)
            }.forEach(Files::delete)
        return result
    }

    override fun close() {
        running = false
        garbageCollector.join()
    }
}