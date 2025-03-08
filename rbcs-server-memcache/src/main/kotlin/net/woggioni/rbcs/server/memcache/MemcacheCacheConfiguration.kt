package net.woggioni.rbcs.server.memcache

import io.netty.channel.ChannelFactory
import io.netty.channel.ChannelHandler
import io.netty.channel.EventLoopGroup
import io.netty.channel.pool.FixedChannelPool
import io.netty.channel.socket.DatagramChannel
import io.netty.channel.socket.SocketChannel
import net.woggioni.rbcs.api.CacheHandler
import net.woggioni.rbcs.api.CacheHandlerFactory
import net.woggioni.rbcs.api.Configuration
import net.woggioni.rbcs.common.HostAndPort
import net.woggioni.rbcs.common.createLogger
import net.woggioni.rbcs.server.memcache.client.MemcacheClient
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class MemcacheCacheConfiguration(
    val servers: List<Server>,
    val maxAge: Duration = Duration.ofDays(1),
    val digestAlgorithm: String? = null,
    val compressionMode: CompressionMode? = null,
    val compressionLevel: Int,
) : Configuration.Cache {

    companion object {
        private val log = createLogger<MemcacheCacheConfiguration>()
    }

    enum class CompressionMode {
        /**
         * Deflate mode
         */
        DEFLATE
    }

    data class Server(
        val endpoint: HostAndPort,
        val connectionTimeoutMillis: Int?,
        val maxConnections: Int
    )

    override fun materialize() = object : CacheHandlerFactory {

        private val connectionPoolMap = ConcurrentHashMap<HostAndPort, FixedChannelPool>()

        override fun newHandler(
            cfg : Configuration,
            eventLoop: EventLoopGroup,
            socketChannelFactory: ChannelFactory<SocketChannel>,
            datagramChannelFactory: ChannelFactory<DatagramChannel>,
        ): CacheHandler {
            return MemcacheCacheHandler(
                MemcacheClient(
                    this@MemcacheCacheConfiguration.servers,
                    cfg.connection.chunkSize,
                    eventLoop,
                    socketChannelFactory,
                    connectionPoolMap
                ),
                digestAlgorithm,
                compressionMode != null,
                compressionLevel,
                cfg.connection.chunkSize,
                maxAge
            )
        }

        override fun asyncClose() = object : CompletableFuture<Void>() {
            init {
                val failure = AtomicReference<Throwable>(null)
                val pools = connectionPoolMap.values.toList()
                val npools = pools.size
                val finished = AtomicInteger(0)
                if (pools.isEmpty()) {
                    complete(null)
                } else {
                    pools.forEach { pool ->
                        pool.closeAsync().addListener {
                            if (!it.isSuccess) {
                                failure.compareAndSet(null, it.cause())
                            }
                            if (finished.incrementAndGet() == npools) {
                                when (val ex = failure.get()) {
                                    null -> complete(null)
                                    else -> completeExceptionally(ex)
                                }
                            }
                        }
                    }
                }
            }
        }

    }

    override fun getNamespaceURI() = "urn:net.woggioni.rbcs.server.memcache"

    override fun getTypeName() = "memcacheCacheType"
}

