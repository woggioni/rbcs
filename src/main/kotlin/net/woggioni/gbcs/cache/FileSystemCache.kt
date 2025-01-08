package net.woggioni.gbcs.cache

import net.woggioni.gbcs.api.Cache
import net.woggioni.jwo.JWO
import net.woggioni.jwo.LockFile
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
import java.util.concurrent.atomic.AtomicReference
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

    private fun lockFilePath(key: String): Path = root.resolve("$key.lock")

    init {
        Files.createDirectories(root)
    }

    private var nextGc = AtomicReference(Instant.now().plus(maxAge))

    override fun get(key: String) = (digestAlgorithm
        ?.let(MessageDigest::getInstance)
        ?.let { md ->
            digestString(key.toByteArray(), md)
        } ?: key).let { digest ->
        LockFile.acquire(lockFilePath(digest), true).use {
            root.resolve(digest).takeIf(Files::exists)?.let { file ->
                if (compressionEnabled) {
                    val inflater = Inflater()
                    Channels.newChannel(InflaterInputStream(Files.newInputStream(file), inflater))
                } else {
                    FileChannel.open(file, StandardOpenOption.READ)
                }
            }
        }.also {
            gc()
        }
    }

    override fun put(key: String, content: ByteArray) {
        (digestAlgorithm
            ?.let(MessageDigest::getInstance)
            ?.let { md ->
                digestString(key.toByteArray(), md)
            } ?: key).let { digest ->
            LockFile.acquire(lockFilePath(digest), false).use {
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
                        it.write(content)
                    }
                    Files.move(tmpFile, file, StandardCopyOption.ATOMIC_MOVE)
                } catch (t: Throwable) {
                    Files.delete(tmpFile)
                    throw t
                }
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

    override fun close() {}

    companion object {
        fun digest(
            data: ByteArray,
            md: MessageDigest = MessageDigest.getInstance("MD5")
        ): ByteArray {
            md.update(data)
            return md.digest()
        }

        fun digestString(
            data: ByteArray,
            md: MessageDigest = MessageDigest.getInstance("MD5")
        ): String {
            return JWO.bytesToHex(digest(data, md))
        }
    }
}