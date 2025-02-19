package net.woggioni.rbcs.server.handler

import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.LastHttpContent
import net.woggioni.rbcs.api.message.CacheMessage.CacheContent
import net.woggioni.rbcs.api.message.CacheMessage.LastCacheContent

@Sharable
object CacheContentHandler : SimpleChannelInboundHandler<HttpContent>() {
    val NAME = this::class.java.name

    override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpContent) {
        when(msg) {
            is LastHttpContent -> {
                ctx.fireChannelRead(LastCacheContent(msg.content().retain()))
                ctx.pipeline().remove(this)
            }
            else -> ctx.fireChannelRead(CacheContent(msg.content().retain()))
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
        super.exceptionCaught(ctx, cause)
    }
}