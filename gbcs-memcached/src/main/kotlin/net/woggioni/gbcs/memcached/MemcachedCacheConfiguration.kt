package net.woggioni.gbcs.memcached

import net.rubyeye.xmemcached.transcoders.CompressionMode
import net.woggioni.gbcs.api.Configuration
import net.woggioni.gbcs.base.HostAndPort
import java.time.Duration

data class MemcachedCacheConfiguration(
    var servers: List<HostAndPort>,
    var maxAge: Duration = Duration.ofDays(1),
    var maxSize: Int = 0x100000,
    var digestAlgorithm: String? = null,
    var compressionMode: CompressionMode = CompressionMode.ZIP,
) : Configuration.Cache {
    override fun materialize() = MemcachedCache(
        servers,
        maxAge,
        maxSize,
        digestAlgorithm,
        compressionMode
    )

    override fun getNamespaceURI() = "urn:net.woggioni.gbcs-memcached"

    override fun getTypeName() = "memcachedCacheType"
}
