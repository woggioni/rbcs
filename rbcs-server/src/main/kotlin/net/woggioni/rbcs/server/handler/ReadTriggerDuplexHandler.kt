package net.woggioni.rbcs.server.handler

import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.LastHttpContent
import net.woggioni.rbcs.common.createLogger
import net.woggioni.rbcs.common.debug

@Sharable
object ReadTriggerDuplexHandler : ChannelDuplexHandler() {
    val NAME = ReadTriggerDuplexHandler::class.java.name

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        ctx.read()
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        super.channelRead(ctx, msg)
        if(msg !is LastHttpContent) {
            ctx.read()
        }
    }

    override fun write(
        ctx: ChannelHandlerContext,
        msg: Any,
        promise: ChannelPromise
    ) {
        super.write(ctx, msg, promise)
        if(msg is LastHttpContent) {
            ctx.read()
        }
    }
}