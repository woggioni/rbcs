package net.woggioni.rbcs.server.memcache.client

import io.netty.handler.codec.memcache.MemcacheContent
import io.netty.handler.codec.memcache.binary.BinaryMemcacheRequest

interface MemcacheRequestController {

    fun sendRequest(request : BinaryMemcacheRequest)

    fun sendContent(content : MemcacheContent)

    fun exceptionCaught(ex : Throwable)
}
