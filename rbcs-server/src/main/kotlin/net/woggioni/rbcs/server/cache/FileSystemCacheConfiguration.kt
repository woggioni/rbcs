package net.woggioni.rbcs.server.cache

import net.woggioni.jwo.Application
import net.woggioni.rbcs.api.CacheHandlerFactory
import net.woggioni.rbcs.api.Configuration
import net.woggioni.rbcs.common.RBCS
import java.nio.file.Path
import java.time.Duration

data class FileSystemCacheConfiguration(
    val root: Path?,
    val maxAge: Duration,
    val digestAlgorithm : String?,
    val compressionEnabled: Boolean,
    val compressionLevel: Int,
    val chunkSize: Int,
) : Configuration.Cache {

    override fun materialize() = object : CacheHandlerFactory {
        private val cache = FileSystemCache(root ?: Application.builder("rbcs").build().computeCacheDirectory(), maxAge)

        override fun close() {
            cache.close()
        }

        override fun newHandler() = FileSystemCacheHandler(cache, digestAlgorithm, compressionEnabled, compressionLevel, chunkSize)
    }

    override fun getNamespaceURI() = RBCS.RBCS_NAMESPACE_URI

    override fun getTypeName() = "fileSystemCacheType"
}