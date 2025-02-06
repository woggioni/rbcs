package net.woggioni.rbcs.common

import io.netty.buffer.ByteBuf
import java.io.OutputStream

class ByteBufOutputStream(private val buf : ByteBuf) : OutputStream() {
    override fun write(b: Int) {
        buf.writeByte(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        buf.writeBytes(b, off, len)
    }

    override fun close() {
        buf.release()
    }
}