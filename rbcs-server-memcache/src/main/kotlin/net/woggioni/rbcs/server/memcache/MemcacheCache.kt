package net.woggioni.rbcs.server.memcache

import io.netty.buffer.ByteBuf
import net.woggioni.rbcs.api.Cache
import net.woggioni.rbcs.server.memcache.client.MemcacheClient
import java.nio.channels.ReadableByteChannel
import java.util.concurrent.CompletableFuture

class MemcacheCache(private val cfg : MemcacheCacheConfiguration) : Cache {
    private val memcacheClient = MemcacheClient(cfg)

    override fun get(key: String): CompletableFuture<ReadableByteChannel?> {
        return memcacheClient.get(key)
    }

    override fun put(key: String, content: ByteBuf): CompletableFuture<Void> {
        return memcacheClient.put(key, content, cfg.maxAge)
    }

    override fun close() {
        memcacheClient.close()
    }
}
