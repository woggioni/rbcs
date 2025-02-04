package net.woggioni.gbcs.server.memcache

import net.woggioni.gbcs.api.Configuration
import net.woggioni.gbcs.common.HostAndPort
import java.time.Duration

data class MemcacheCacheConfiguration(
    val servers: List<Server>,
    val maxAge: Duration = Duration.ofDays(1),
    val maxSize: Int = 0x100000,
    val digestAlgorithm: String? = null,
    val compressionMode: CompressionMode? = null,
) : Configuration.Cache {

    enum class CompressionMode {
        /**
         * Gzip mode
         */
        GZIP,

        /**
         * Deflate mode
         */
        DEFLATE
    }

    data class Server(
        val endpoint : HostAndPort,
        val connectionTimeoutMillis : Int?,
        val maxConnections : Int
    )


    override fun materialize() = MemcacheCache(this)

    override fun getNamespaceURI() = "urn:net.woggioni.gbcs.server.memcache"

    override fun getTypeName() = "memcacheCacheType"
}

