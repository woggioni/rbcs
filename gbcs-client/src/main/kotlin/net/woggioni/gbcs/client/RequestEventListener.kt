package net.woggioni.gbcs.client

import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse

interface RequestEventListener {
    fun requestSent(req : FullHttpRequest) {}
    fun responseReceived(res : FullHttpResponse) {}
    fun exceptionCaught(ex : Throwable) {}
}