package net.woggioni.gbcs.server.cache

import net.woggioni.gbcs.api.Configuration
import net.woggioni.gbcs.common.GBCS
import java.time.Duration

data class InMemoryCacheConfiguration(
    val maxAge: Duration,
    val maxSize: Long,
    val digestAlgorithm : String?,
    val compressionEnabled: Boolean,
    val compressionLevel: Int,
) : Configuration.Cache {
    override fun materialize() = InMemoryCache(
        maxAge,
        maxSize,
        digestAlgorithm,
        compressionEnabled,
        compressionLevel
    )

    override fun getNamespaceURI() = GBCS.GBCS_NAMESPACE_URI

    override fun getTypeName() = "inMemoryCacheType"
}