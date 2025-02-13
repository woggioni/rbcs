package net.woggioni.rbcs.server.memcache.client

import io.netty.buffer.ByteBuf
import io.netty.handler.codec.memcache.LastMemcacheContent
import io.netty.handler.codec.memcache.MemcacheContent
import io.netty.handler.codec.memcache.binary.BinaryMemcacheRequest
import io.netty.handler.codec.memcache.binary.BinaryMemcacheResponse

sealed interface StreamingRequestEvent {
    class SendRequest(val request : BinaryMemcacheRequest) : StreamingRequestEvent
    open class SendChunk(val chunk : ByteBuf) : StreamingRequestEvent
    class SendLastChunk(chunk : ByteBuf) : SendChunk(chunk)
    class ExceptionCaught(val exception : Throwable) : StreamingRequestEvent
}

sealed interface StreamingResponseEvent {
    class ResponseReceived(val response : BinaryMemcacheResponse) : StreamingResponseEvent
    open class ContentReceived(val content : MemcacheContent) : StreamingResponseEvent
    class LastContentReceived(val lastContent : LastMemcacheContent) : ContentReceived(lastContent)
    class ExceptionCaught(val exception : Throwable) : StreamingResponseEvent
}

interface MemcacheRequestHandle {
    fun handleEvent(evt : StreamingRequestEvent)
}

interface MemcacheResponseHandle {
    fun handleEvent(evt : StreamingResponseEvent)
}

