package net.woggioni.gbcs.server.memcached

import io.netty.buffer.Unpooled
import io.netty.handler.codec.memcache.binary.BinaryMemcacheResponseStatus
import net.woggioni.gbcs.api.Cache
import net.woggioni.gbcs.api.CallHandle
import net.woggioni.gbcs.api.ResponseEventListener
import net.woggioni.gbcs.api.event.RequestEvent
import net.woggioni.gbcs.api.event.ResponseEvent
import net.woggioni.gbcs.server.memcached.client.MemcachedClient
import net.woggioni.gbcs.server.memcached.client.ResponseEvent.ExceptionCaught
import net.woggioni.gbcs.server.memcached.client.ResponseEvent.LastResponseContentChunkReceived
import net.woggioni.gbcs.server.memcached.client.ResponseEvent.ResponseContentChunkReceived
import net.woggioni.gbcs.server.memcached.client.ResponseEvent.ResponseReceived
import net.woggioni.gbcs.server.memcached.client.ResponseListener
import java.util.concurrent.CompletableFuture

class MemcachedCache(
    private val cfg : MemcachedCacheConfiguration
) : Cache {
    private val client = MemcachedClient(cfg)

    override fun close() {
        client.close()
    }

    override fun get(key: String, responseEventListener: ResponseEventListener): CompletableFuture<CallHandle<Void>> {
        val listener = ResponseListener { evt ->
            when(evt) {
                is ResponseContentChunkReceived -> {
                    responseEventListener.listen(ResponseEvent.ChunkReceived(Unpooled.wrappedBuffer(evt.chunk)))
                }
                is LastResponseContentChunkReceived -> {
                    responseEventListener.listen(ResponseEvent.LastChunkReceived(Unpooled.wrappedBuffer(evt.chunk)))
                }
                is ExceptionCaught -> {
                    responseEventListener.listen(ResponseEvent.ExceptionCaught(evt.cause))
                }
                is ResponseReceived -> {
                    when(val status = evt.response.status) {
                        BinaryMemcacheResponseStatus.SUCCESS -> {
                        }
                        BinaryMemcacheResponseStatus.KEY_ENOENT -> {
                            responseEventListener.listen(ResponseEvent.NoContent())
                        }
                        else -> {
                            responseEventListener.listen(ResponseEvent.ExceptionCaught(MemcachedException(status)))
                        }
                    }
                }
            }
        }
        return client.get(key, listener).thenApply { clientCallHandle ->
            object : CallHandle<Void> {
                override fun postEvent(evt: RequestEvent) {
                    when(evt) {
                        is RequestEvent.ChunkSent -> clientCallHandle.sendChunk(evt.chunk.nioBuffer())
                        is RequestEvent.LastChunkSent -> clientCallHandle.sendChunk(evt.chunk.nioBuffer())
                    }
                }

                override fun call(): CompletableFuture<Void> {
                    return clientCallHandle.waitForResponse().thenApply { null }
                }
            }
        }
    }

    override fun put(key: String): CompletableFuture<CallHandle<Void>> {
        return client.put(key, cfg.maxAge).thenApply { clientCallHandle ->
            object : CallHandle<Void> {
                override fun postEvent(evt: RequestEvent) {
                    when(evt) {
                        is RequestEvent.ChunkSent -> clientCallHandle.sendChunk(evt.chunk.nioBuffer())
                        is RequestEvent.LastChunkSent -> clientCallHandle.sendChunk(evt.chunk.nioBuffer())
                    }
                }

                override fun call(): CompletableFuture<Void> {
                    return clientCallHandle.waitForResponse().thenApply { null }
                }
            }
        }
    }
}
