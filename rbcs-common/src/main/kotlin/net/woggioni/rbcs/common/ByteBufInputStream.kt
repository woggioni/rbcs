package net.woggioni.rbcs.common

import java.io.InputStream
import io.netty.buffer.ByteBuf

class ByteBufInputStream(private val buf : ByteBuf) : InputStream() {
    override fun read(): Int {
        return buf.takeIf {
            it.readableBytes() > 0
        }?.let(ByteBuf::readByte)
            ?.let(Byte::toInt) ?: -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val readableBytes = buf.readableBytes()
        if(readableBytes == 0) return -1
        val result = len.coerceAtMost(readableBytes)
        buf.readBytes(b, off, result)
        return result
    }

    override fun close() {
        buf.release()
    }
}