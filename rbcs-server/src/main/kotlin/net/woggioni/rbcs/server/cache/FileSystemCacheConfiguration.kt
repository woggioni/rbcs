package net.woggioni.rbcs.server.cache

import io.netty.channel.ChannelFactory
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.DatagramChannel
import io.netty.channel.socket.SocketChannel
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
) : Configuration.Cache {

    override fun materialize() = object : CacheHandlerFactory {
        private val cache = FileSystemCache(root ?: Application.builder("rbcs").build().computeCacheDirectory(), maxAge)

        override fun asyncClose() = cache.asyncClose()

        override fun newHandler(
            cfg : Configuration,
            eventLoop: EventLoopGroup,
            socketChannelFactory: ChannelFactory<SocketChannel>,
            datagramChannelFactory: ChannelFactory<DatagramChannel>
        ) = FileSystemCacheHandler(cache, digestAlgorithm, compressionEnabled, compressionLevel, cfg.connection.chunkSize)
    }

    override fun getNamespaceURI() = RBCS.RBCS_NAMESPACE_URI

    override fun getTypeName() = "fileSystemCacheType"
}