package net.woggioni.rbcs.server.memcache.client

import io.netty.channel.Channel
import io.netty.handler.codec.memcache.MemcacheContent
import io.netty.handler.codec.memcache.binary.BinaryMemcacheRequest

interface MemcacheRequestController {

    val channel: Channel

    fun sendRequest(request : BinaryMemcacheRequest)

    fun sendContent(content : MemcacheContent)

    fun exceptionCaught(ex : Throwable)
}
