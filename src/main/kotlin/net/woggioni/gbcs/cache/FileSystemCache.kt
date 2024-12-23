package net.woggioni.gbcs.cache

import io.netty.buffer.ByteBuf
import net.woggioni.gbcs.GradleBuildCacheServer.Companion.digestString
import net.woggioni.jwo.LockFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference


class FileSystemCache(val root: Path, val maxAge: Duration) : Cache {

    private fun lockFilePath(key: String): Path = root.resolve("$key.lock")

    init {
        Files.createDirectories(root)
    }

    override fun equals(other: Any?): Boolean {
        return when (other) {
            is FileSystemCache -> {
                other.root == root && other.maxAge == maxAge
            }

            else -> false
        }
    }

    override fun hashCode(): Int {
        return root.hashCode() xor maxAge.hashCode()
    }

    private var nextGc = AtomicReference(Instant.now().plus(maxAge))

    override fun get(key: String) = LockFile.acquire(lockFilePath(key), true).use {
        root.resolve(key).takeIf(Files::exists)?.let { FileChannel.open(it, StandardOpenOption.READ) }
    }.also {
        gc()
    }

    override fun put(key: String, content: ByteBuf) {
        LockFile.acquire(lockFilePath(key), false).use {
            val file = root.resolve(key)
            val tmpFile = Files.createTempFile(root, null, ".tmp")
            try {
                Files.newOutputStream(tmpFile).use {
                    content.readBytes(it, content.readableBytes())
                }
                Files.move(tmpFile, file, StandardCopyOption.ATOMIC_MOVE)
            } catch (t: Throwable) {
                Files.delete(tmpFile)
                throw t
            }
        }.also {
            gc()
        }
    }

    private fun gc() {
        val now = Instant.now()
        val oldValue = nextGc.getAndSet(now.plus(maxAge))
        if (oldValue < now) {
            actualGc(now)
        }
    }

    @Synchronized
    private fun actualGc(now: Instant) {
        Files.list(root).filter {
            !it.fileName.toString().endsWith(".lock")
        }.filter {
            val creationTimeStamp = Files.readAttributes(it, BasicFileAttributes::class.java)
                .creationTime()
                .toInstant()
            now > creationTimeStamp.plus(maxAge)
        }.forEach { file ->
            val lockFile = lockFilePath(file.fileName.toString())
            LockFile.acquire(lockFile, false).use {
                Files.delete(file)
            }
            Files.delete(lockFile)
        }
    }
}