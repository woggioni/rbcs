package net.woggioni.rbcs.server.cache

import io.netty.channel.ChannelFactory
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.DatagramChannel
import io.netty.channel.socket.SocketChannel
import java.time.Duration
import net.woggioni.rbcs.api.CacheHandlerFactory
import net.woggioni.rbcs.api.Configuration
import net.woggioni.rbcs.common.RBCS

data class InMemoryCacheConfiguration(
    val maxAge: Duration,
    val maxSize: Long,
    val digestAlgorithm : String?,
    val compressionEnabled: Boolean,
    val compressionLevel: Int,
) : Configuration.Cache {
    override fun materialize() = object : CacheHandlerFactory {
        private val cache = InMemoryCache(maxAge, maxSize)

        override fun asyncClose() = cache.asyncClose()

        override fun newHandler(
            cfg : Configuration,
            eventLoop: EventLoopGroup,
            socketChannelFactory: ChannelFactory<SocketChannel>,
            datagramChannelFactory: ChannelFactory<DatagramChannel>
        ) = InMemoryCacheHandler(cache, digestAlgorithm, compressionEnabled, compressionLevel)
    }

    override fun getNamespaceURI() = RBCS.RBCS_NAMESPACE_URI

    override fun getTypeName() = "inMemoryCacheType"
}