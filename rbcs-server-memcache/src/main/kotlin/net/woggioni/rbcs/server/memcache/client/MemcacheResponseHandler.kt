package net.woggioni.rbcs.server.memcache.client

import io.netty.handler.codec.memcache.MemcacheContent
import io.netty.handler.codec.memcache.binary.BinaryMemcacheResponse

interface MemcacheResponseHandler {


    fun responseReceived(response : BinaryMemcacheResponse)

    fun contentReceived(content : MemcacheContent)

    fun exceptionCaught(ex : Throwable)
}
