package net.woggioni.rbcs.server.cache

import net.woggioni.rbcs.api.Configuration
import net.woggioni.rbcs.common.RBCS
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
        root ?: Application.builder("rbcs").build().computeCacheDirectory(),
        maxAge,
        digestAlgorithm,
        compressionEnabled,
        compressionLevel
    )

    override fun getNamespaceURI() = RBCS.RBCS_NAMESPACE_URI

    override fun getTypeName() = "fileSystemCacheType"
}