package net.woggioni.rbcs.server.cache

import net.woggioni.jwo.JWO
import net.woggioni.rbcs.api.CacheValueMetadata
import net.woggioni.rbcs.common.createLogger
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant

class FileSystemCache(
    val root: Path,
    val maxAge: Duration
) : AutoCloseable {

    class EntryValue(val metadata: CacheValueMetadata, val channel : FileChannel, val offset : Long, val size : Long) : Serializable

    private companion object {
        private val log = createLogger<FileSystemCache>()
    }

    init {
        Files.createDirectories(root)
    }

    @Volatile
    private var running = true

    private var nextGc = Instant.now()

    fun get(key: String): EntryValue? =
        root.resolve(key).takeIf(Files::exists)
            ?.let { file ->
                val size = Files.size(file)
                val channel = FileChannel.open(file, StandardOpenOption.READ)
                val source = Channels.newInputStream(channel)
                val tmp = ByteArray(Integer.BYTES)
                val buffer = ByteBuffer.wrap(tmp)
                source.read(tmp)
                buffer.rewind()
                val offset = (Integer.BYTES + buffer.getInt()).toLong()
                var count = 0
                val wrapper = object : InputStream() {
                    override fun read(): Int {
                        return source.read().also {
                            if (it > 0) count += it
                        }
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        return source.read(b, off, len).also {
                            if (it > 0) count += it
                        }
                    }

                    override fun close() {
                    }
                }
                val metadata = ObjectInputStream(wrapper).use { ois ->
                    ois.readObject() as CacheValueMetadata
                }
                EntryValue(metadata, channel, offset, size)
            }

    class FileSink(metadata: CacheValueMetadata, private val path: Path, private val tmpFile: Path) {
        val channel: FileChannel

        init {
            val baos = ByteArrayOutputStream()
            ObjectOutputStream(baos).use {
                it.writeObject(metadata)
            }
            Files.newOutputStream(tmpFile).use {
                val bytes = baos.toByteArray()
                val buffer = ByteBuffer.allocate(Integer.BYTES)
                buffer.putInt(bytes.size)
                buffer.rewind()
                it.write(buffer.array())
                it.write(bytes)
            }
            channel = FileChannel.open(tmpFile, StandardOpenOption.APPEND)
        }

        fun commit() {
            channel.close()
            Files.move(tmpFile, path, StandardCopyOption.ATOMIC_MOVE)
        }

        fun rollback() {
            channel.close()
            Files.delete(path)
        }
    }

    fun put(
        key: String,
        metadata: CacheValueMetadata,
    ): FileSink {
        val file = root.resolve(key)
        val tmpFile = Files.createTempFile(root, null, ".tmp")
        return FileSink(metadata, file, tmpFile)
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
    private fun actualGc(now: Instant): Instant? {
        var result: Instant? = null
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
                if (result == null || creationTimeStamp < result) {
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