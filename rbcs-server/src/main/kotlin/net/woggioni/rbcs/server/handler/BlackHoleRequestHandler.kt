package net.woggioni.rbcs.server.handler

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.HttpContent

class BlackHoleRequestHandler : SimpleChannelInboundHandler<HttpContent>() {
    companion object {
        val NAME = BlackHoleRequestHandler::class.java.name
    }
    override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpContent) {
    }
}