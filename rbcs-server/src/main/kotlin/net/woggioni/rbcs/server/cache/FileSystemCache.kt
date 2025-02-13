package net.woggioni.rbcs.server.cache

import io.netty.buffer.ByteBufAllocator
import net.woggioni.jwo.JWO
import net.woggioni.rbcs.api.Cache
import net.woggioni.rbcs.api.RequestHandle
import net.woggioni.rbcs.api.ResponseHandle
import net.woggioni.rbcs.api.event.RequestStreamingEvent
import net.woggioni.rbcs.api.event.ResponseStreamingEvent
import net.woggioni.rbcs.common.ByteBufOutputStream
import net.woggioni.rbcs.common.RBCS.digestString
import net.woggioni.rbcs.common.contextLogger
import net.woggioni.rbcs.common.extractChunk
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
import java.util.zip.InflaterInputStream

class FileSystemCache(
    val root: Path,
    val maxAge: Duration,
    val digestAlgorithm: String?,
    val compressionEnabled: Boolean,
    val compressionLevel: Int,
    val chunkSize: Int
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

    override fun get(key: String, responseHandle: ResponseHandle, alloc: ByteBufAllocator) {
        (digestAlgorithm
            ?.let(MessageDigest::getInstance)
            ?.let { md ->
                digestString(key.toByteArray(), md)
            } ?: key).let { digest ->
            root.resolve(digest).takeIf(Files::exists)
                ?.let { file ->
                    file.takeIf(Files::exists)?.let { file ->
                        responseHandle.handleEvent(ResponseStreamingEvent.RESPONSE_RECEIVED)
                        if (compressionEnabled) {
                            val compositeBuffer = alloc.compositeBuffer()
                            ByteBufOutputStream(compositeBuffer).use { outputStream ->
                                InflaterInputStream(Files.newInputStream(file)).use { inputStream ->
                                    val ioBuffer = alloc.buffer(chunkSize)
                                    try {
                                        while (true) {
                                            val read = ioBuffer.writeBytes(inputStream, chunkSize)
                                            val last = read < 0
                                            if (read > 0) {
                                                ioBuffer.readBytes(outputStream, read)
                                            }
                                            if (last) {
                                                compositeBuffer.retain()
                                                outputStream.close()
                                            }
                                            if (compositeBuffer.readableBytes() >= chunkSize || last) {
                                                val chunk = extractChunk(compositeBuffer, alloc)
                                                val evt = if (last) {
                                                    ResponseStreamingEvent.LastChunkReceived(chunk)
                                                } else {
                                                    ResponseStreamingEvent.ChunkReceived(chunk)
                                                }
                                                responseHandle.handleEvent(evt)
                                            }
                                            if (last) break
                                        }
                                    } finally {
                                        ioBuffer.release()
                                    }
                                }
                            }
                        } else {
                            responseHandle.handleEvent(
                                ResponseStreamingEvent.FileReceived(
                                    FileChannel.open(file, StandardOpenOption.READ)
                                )
                            )
                        }
                    }
                } ?: responseHandle.handleEvent(ResponseStreamingEvent.NOT_FOUND)
        }
    }

    override fun put(
        key: String,
        responseHandle: ResponseHandle,
        alloc: ByteBufAllocator
    ): CompletableFuture<RequestHandle> {
        try {
            (digestAlgorithm
                ?.let(MessageDigest::getInstance)
                ?.let { md ->
                    digestString(key.toByteArray(), md)
                } ?: key).let { digest ->
                val file = root.resolve(digest)
                val tmpFile = Files.createTempFile(root, null, ".tmp")
                val stream = Files.newOutputStream(tmpFile).let {
                    if (compressionEnabled) {
                        val deflater = Deflater(compressionLevel)
                        DeflaterOutputStream(it, deflater)
                    } else {
                        it
                    }
                }
                return CompletableFuture.completedFuture(object : RequestHandle {
                    override fun handleEvent(evt: RequestStreamingEvent) {
                        try {
                            when (evt) {
                                is RequestStreamingEvent.LastChunkReceived -> {
                                    evt.chunk.readBytes(stream, evt.chunk.readableBytes())
                                    stream.close()
                                    Files.move(tmpFile, file, StandardCopyOption.ATOMIC_MOVE)
                                    responseHandle.handleEvent(ResponseStreamingEvent.RESPONSE_RECEIVED)
                                }

                                is RequestStreamingEvent.ChunkReceived -> {
                                    evt.chunk.readBytes(stream, evt.chunk.readableBytes())
                                }

                                is RequestStreamingEvent.ExceptionCaught -> {
                                    Files.delete(tmpFile)
                                    stream.close()
                                }
                            }
                        } catch (ex: Throwable) {
                            responseHandle.handleEvent(ResponseStreamingEvent.ExceptionCaught(ex))
                        }
                    }
                })
            }
        } catch (ex: Throwable) {
            responseHandle.handleEvent(ResponseStreamingEvent.ExceptionCaught(ex))
            return CompletableFuture.failedFuture(ex)
        }
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