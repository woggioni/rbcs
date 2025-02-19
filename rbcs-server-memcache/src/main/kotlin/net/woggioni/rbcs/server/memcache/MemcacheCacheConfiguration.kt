package net.woggioni.rbcs.server.memcache

import net.woggioni.rbcs.api.CacheHandlerFactory
import net.woggioni.rbcs.api.Configuration
import net.woggioni.rbcs.common.HostAndPort
import net.woggioni.rbcs.server.memcache.client.MemcacheClient
import java.time.Duration

data class MemcacheCacheConfiguration(
    val servers: List<Server>,
    val maxAge: Duration = Duration.ofDays(1),
    val digestAlgorithm: String? = null,
    val compressionMode: CompressionMode? = null,
    val compressionLevel: Int,
    val chunkSize : Int
) : Configuration.Cache {

    enum class CompressionMode {
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


    override fun materialize() = object : CacheHandlerFactory {
        private val client = MemcacheClient(this@MemcacheCacheConfiguration.servers, chunkSize)
        override fun close() {
            client.close()
        }

        override fun newHandler() = MemcacheCacheHandler(client, digestAlgorithm, compressionMode != null, compressionLevel, chunkSize, maxAge)
    }

    override fun getNamespaceURI() = "urn:net.woggioni.rbcs.server.memcache"

    override fun getTypeName() = "memcacheCacheType"
}

