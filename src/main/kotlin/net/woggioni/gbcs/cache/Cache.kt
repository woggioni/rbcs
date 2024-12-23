package net.woggioni.gbcs.cache

import io.netty.buffer.ByteBuf
import java.nio.channels.ByteChannel

interface Cache {
    fun get(key : String) : ByteChannel?

    fun put(key : String, content : ByteBuf) : Unit
}