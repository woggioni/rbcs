package net.woggioni.rbcs.server.memcache.client

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import kotlin.random.Random

class ByteBufferTest {

    @Test
    fun test() {
        val byteBuffer = ByteBuffer.allocate(0x100)
        val originalBytes = Random(101325).nextBytes(0x100)
        Channels.newChannel(ByteArrayInputStream(originalBytes)).use { source ->
            source.read(byteBuffer)
        }
        byteBuffer.flip()
        val buf = Unpooled.buffer()
        buf.writeBytes(byteBuffer)
        val finalBytes = ByteBufUtil.getBytes(buf)
        Assertions.assertArrayEquals(originalBytes, finalBytes)
    }
}