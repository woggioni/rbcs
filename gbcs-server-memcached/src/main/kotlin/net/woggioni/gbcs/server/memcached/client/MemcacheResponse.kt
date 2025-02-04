package net.woggioni.gbcs.server.memcached.client

import io.netty.handler.codec.memcache.binary.BinaryMemcacheResponse
import java.nio.ByteBuffer

data class MemcacheResponse(
    val status: Short,
    val opcode: Byte,
    val cas: Long?,
    val opaque: Int?,
    val key: ByteBuffer?,
    val extra: ByteBuffer?
) {
    companion object {
        fun of(response : BinaryMemcacheResponse) = MemcacheResponse(
            response.status(),
            response.opcode(),
            response.cas(),
            response.opaque(),
            response.key()?.nioBuffer(),
            response.extras()?.nioBuffer()
        )
    }
}