package net.woggioni.rbcs.server.handler

import io.netty.buffer.ByteBufHolder
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.LastHttpContent
import net.woggioni.rbcs.common.createLogger

class ReadTriggerDuplexHandler : ChannelDuplexHandler() {
    companion object {
        val NAME = ReadTriggerDuplexHandler::class.java.name
        private val log = createLogger<ReadTriggerDuplexHandler>()
    }

    private var inFlight = 0
    private val messageBuffer = ArrayDeque<Any>()

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        ctx.read()
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if(inFlight > 0) {
            messageBuffer.addLast(msg)
        } else {
            super.channelRead(ctx, msg)
            if(msg !is LastHttpContent) {
                invokeRead(ctx)
            } else {
                inFlight += 1
            }
        }
    }

    private fun invokeRead(ctx : ChannelHandlerContext) {
        if(messageBuffer.isEmpty()) {
            ctx.read()
        } else {
            this.channelRead(ctx, messageBuffer.removeFirst())
        }
    }

    override fun write(
        ctx: ChannelHandlerContext,
        msg: Any,
        promise: ChannelPromise
    ) {
        super.write(ctx, msg, promise)
        if(msg is LastHttpContent) {
            inFlight -= 1
            invokeRead(ctx)
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        while(messageBuffer.isNotEmpty()) {
            val msg = messageBuffer.removeFirst()
            if(msg is ByteBufHolder) {
                msg.release()
            }
        }
        super.channelInactive(ctx)
    }
}