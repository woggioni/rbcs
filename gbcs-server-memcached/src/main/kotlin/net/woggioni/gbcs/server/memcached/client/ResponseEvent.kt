package net.woggioni.gbcs.server.memcached.client

import java.nio.ByteBuffer

sealed interface ResponseEvent {
    class ResponseReceived(val response : MemcacheResponse) : ResponseEvent
    class ResponseContentChunkReceived(val chunk: ByteBuffer) : ResponseEvent
    class LastResponseContentChunkReceived(val chunk: ByteBuffer) : ResponseEvent
    class ExceptionCaught(val cause : Throwable) : ResponseEvent
}