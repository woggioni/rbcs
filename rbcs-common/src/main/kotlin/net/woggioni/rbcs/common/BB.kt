package net.woggioni.rbcs.common

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.CompositeByteBuf

fun extractChunk(buf: CompositeByteBuf, alloc: ByteBufAllocator): ByteBuf {
    val chunk = alloc.compositeBuffer()
    for (component in buf.decompose(0, buf.readableBytes())) {
        chunk.addComponent(true, component.retain())
    }
    buf.removeComponents(0, buf.numComponents())
    buf.clear()
    return chunk
}