package net.woggioni.gbcs.server.memcached.client

import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture

interface CallHandle {
    fun sendChunk(requestBodyChunk : ByteBuffer)
    fun waitForResponse() : CompletableFuture<Short>
}