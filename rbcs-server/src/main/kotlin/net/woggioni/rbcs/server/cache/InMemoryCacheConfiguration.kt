package net.woggioni.rbcs.server.cache

import net.woggioni.rbcs.api.Configuration
import net.woggioni.rbcs.common.RBCS
import java.time.Duration

data class InMemoryCacheConfiguration(
    val maxAge: Duration,
    val maxSize: Long,
    val digestAlgorithm : String?,
    val compressionEnabled: Boolean,
    val compressionLevel: Int,
    val chunkSize : Int
) : Configuration.Cache {
    override fun materialize() = InMemoryCache(
        maxAge,
        maxSize,
        digestAlgorithm,
        compressionEnabled,
        compressionLevel,
        chunkSize
    )

    override fun getNamespaceURI() = RBCS.RBCS_NAMESPACE_URI

    override fun getTypeName() = "inMemoryCacheType"
}