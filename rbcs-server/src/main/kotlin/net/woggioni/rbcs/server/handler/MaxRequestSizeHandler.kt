package net.woggioni.rbcs.server.handler

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpRequest
import net.woggioni.rbcs.api.exception.ContentTooLargeException


class MaxRequestSizeHandler(private val maxRequestSize : Int) : ChannelInboundHandlerAdapter() {
    companion object {
        val NAME = MaxRequestSizeHandler::class.java.name
    }

    private var cumulativeSize = 0

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when(msg) {
            is HttpRequest -> {
                cumulativeSize = 0
                ctx.fireChannelRead(msg)
            }
            is HttpContent -> {
                val exceeded = cumulativeSize > maxRequestSize
                if(!exceeded) {
                    cumulativeSize += msg.content().readableBytes()
                }
                if(cumulativeSize > maxRequestSize) {
                    msg.release()
                    if(!exceeded) {
                        ctx.fireExceptionCaught(ContentTooLargeException("Request body is too large", null))
                    }
                } else {
                    ctx.fireChannelRead(msg)
                }
            }
            else -> ctx.fireChannelRead(msg)
        }
    }
}