package net.woggioni.gbcs.server.memcached

import net.woggioni.gbcs.api.Configuration
import net.woggioni.gbcs.common.HostAndPort
import java.time.Duration

data class MemcachedCacheConfiguration(
    val servers: List<Server>,
    val maxAge: Duration = Duration.ofDays(1),
    val maxSize: Int = 0x100000,
    val digestAlgorithm: String? = null,
    val compressionMode: CompressionMode? = CompressionMode.DEFLATE,
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

    class RetryPolicy(
        val maxAttempts: Int,
        val initialDelayMillis: Long,
        val exp: Double
    )

    data class Server(
        val endpoint : HostAndPort,
        val connectionTimeoutMillis : Int?,
        val retryPolicy : RetryPolicy?,
        val maxConnections : Int
    )


    override fun materialize() = MemcachedCache(this)

    override fun getNamespaceURI() = "urn:net.woggioni.gbcs.server.memcached"

    override fun getTypeName() = "memcachedCacheType"
}
