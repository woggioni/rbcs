package net.woggioni.gbcs.server.memcached

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.stream.ChunkedInput
import java.nio.channels.ReadableByteChannel

class CustomChunkedInput(private val readableByteChannel: ReadableByteChannel) : ChunkedInput<ByteBuf> {
    override fun isEndOfInput(): Boolean {
        TODO("Not yet implemented")
    }

    override fun close() {
        TODO("Not yet implemented")
    }

    override fun readChunk(ctx: ChannelHandlerContext): ByteBuf {
        TODO("Not yet implemented")
    }

    override fun readChunk(allocator: ByteBufAllocator): ByteBuf {
        TODO("Not yet implemented")
    }

    override fun length(): Long {
        TODO("Not yet implemented")
    }

    override fun progress(): Long {
        TODO("Not yet implemented")
    }
}