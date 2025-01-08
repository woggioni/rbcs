package net.woggioni.gbcs.memcached

import net.rubyeye.xmemcached.MemcachedClient
import net.rubyeye.xmemcached.XMemcachedClientBuilder
import net.rubyeye.xmemcached.command.BinaryCommandFactory
import net.rubyeye.xmemcached.transcoders.CompressionMode
import net.rubyeye.xmemcached.transcoders.SerializingTranscoder
import net.woggioni.gbcs.api.Cache
import net.woggioni.gbcs.api.exception.ContentTooLargeException
import net.woggioni.gbcs.base.HostAndPort
import net.woggioni.jwo.JWO
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

class MemcachedCache(
    servers: List<HostAndPort>,
    private val maxAge: Duration,
    maxSize : Int,
    digestAlgorithm: String?,
    compressionMode: CompressionMode,
) : Cache {
    private val memcachedClient = XMemcachedClientBuilder(
        servers.stream().map { addr: HostAndPort -> InetSocketAddress(addr.host, addr.port) }.toList()
    ).apply {
        commandFactory = BinaryCommandFactory()
        digestAlgorithm?.let { dAlg ->
            setKeyProvider { key ->
                val md = MessageDigest.getInstance(dAlg)
                md.update(key.toByteArray(StandardCharsets.UTF_8))
                JWO.bytesToHex(md.digest())
            }
        }
        transcoder = SerializingTranscoder(maxSize).apply {
            setCompressionMode(compressionMode)
        }
    }.build()

    override fun get(key: String): ReadableByteChannel? {
        return memcachedClient.get<ByteArray>(key)
            ?.let(::ByteArrayInputStream)
            ?.let(Channels::newChannel)
    }

    override fun put(key: String, content: ByteArray) {
        try {
            memcachedClient[key, maxAge.toSeconds().toInt()] = content
        } catch (e: IllegalArgumentException) {
            throw ContentTooLargeException(e.message, e)
        }
    }

    override fun close() {
        memcachedClient.shutdown()
    }
}
