package net.woggioni.gbcs.cache

import net.woggioni.gbcs.api.Configuration
import net.woggioni.gbcs.base.GBCS
import net.woggioni.jwo.Application
import java.nio.file.Path
import java.time.Duration

data class FileSystemCacheConfiguration(
    val root: Path?,
    val maxAge: Duration,
    val digestAlgorithm : String?,
    val compressionEnabled: Boolean,
    val compressionLevel: Int,
) : Configuration.Cache {
    override fun materialize() = FileSystemCache(
        root ?: Application.builder("gbcs").build().computeCacheDirectory(),
        maxAge,
        digestAlgorithm,
        compressionEnabled,
        compressionLevel
    )

    override fun getNamespaceURI() = GBCS.GBCS_NAMESPACE_URI

    override fun getTypeName() = "fileSystemCacheType"
}