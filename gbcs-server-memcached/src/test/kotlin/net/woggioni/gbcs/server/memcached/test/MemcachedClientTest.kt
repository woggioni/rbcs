package net.woggioni.gbcs.server.memcached.test

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.handler.codec.memcache.binary.BinaryMemcacheResponse
import io.netty.handler.codec.memcache.binary.BinaryMemcacheResponseStatus
import net.woggioni.gbcs.api.event.ChunkReceived
import net.woggioni.gbcs.common.HostAndPort

import net.woggioni.gbcs.server.memcached.MemcachedCacheConfiguration
import net.woggioni.gbcs.server.memcached.client.MemcacheResponse
import net.woggioni.gbcs.server.memcached.client.MemcachedClient
import net.woggioni.gbcs.server.memcached.client.ResponseEvent
import net.woggioni.gbcs.server.memcached.client.ResponseListener
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Duration
import java.util.Objects
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class MemcachedClientTest {

    @Test
    fun test() {
        val client = MemcachedClient(MemcachedCacheConfiguration(
            servers = listOf(
                MemcachedCacheConfiguration.Server(
                    endpoint = HostAndPort("127.0.0.1", 11211),
                    connectionTimeoutMillis = null,
                    retryPolicy = null,
                    maxConnections = 1
                )
            )
        ))

        val random = Random(SecureRandom.getInstance("NativePRNGNonBlocking").nextLong())
        val key = "101325"
        val value = random.nextBytes(0x1000)
        val requestListener = client.put(key, Duration.ofDays(2), null)

        val response = Unpooled.buffer(value.size)
        requestListener.thenCompose { listener ->
            listener.sendChunk(ByteBuffer.wrap(value))
            listener.waitForResponse()
        }.get(10, TimeUnit.SECONDS)

        client.get(key, object: ResponseListener {
            override fun listen(evt: ResponseEvent) {
                when(evt) {
                    is ResponseEvent.ResponseReceived -> {
                        if(evt.response.status != BinaryMemcacheResponseStatus.SUCCESS) {
                            Assertions.fail<String> {
                                "Memcache status ${evt.response.status}"
                            }
                        }
                    }
                    is ResponseEvent.ResponseContentChunkReceived -> response.writeBytes(evt.chunk)
                    else -> {}
                }
            }
        }).thenCompose { it.waitForResponse() }.get(1, TimeUnit.SECONDS)
        val retrievedResponse = response.array()
        Assertions.assertArrayEquals(value, retrievedResponse)

    }

    @Test
    fun test2() {
        val a1 = ByteArray(10) {
            it.toByte()
        }
        val a2 = ByteArray(10) {
            it.toByte()
        }
        Assertions.assertTrue(Objects.equals(a1, a1))
    }
}