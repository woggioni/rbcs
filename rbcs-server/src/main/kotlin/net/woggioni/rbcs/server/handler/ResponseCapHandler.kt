package net.woggioni.rbcs.server.handler

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandler
import io.netty.channel.ChannelPromise
import net.woggioni.rbcs.server.event.RequestCompletedEvent
import java.net.SocketAddress

class ResponseCapHandler : ChannelInboundHandlerAdapter(), ChannelOutboundHandler {
    val bufferedMessages = mutableListOf<Any>()

    override fun bind(ctx: ChannelHandlerContext, localAddress: SocketAddress, promise: ChannelPromise) {
        ctx.bind(localAddress, promise)
    }

    override fun connect(
        ctx: ChannelHandlerContext,
        remoteAddress: SocketAddress,
        localAddress: SocketAddress,
        promise: ChannelPromise
    ) {
        ctx.connect(remoteAddress, localAddress, promise)
    }

    override fun disconnect(ctx: ChannelHandlerContext, promise: ChannelPromise) {
        ctx.disconnect(promise)
    }

    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
        ctx.close(promise)
    }

    override fun deregister(ctx: ChannelHandlerContext, promise: ChannelPromise) {
        ctx.deregister(promise)
    }

    override fun read(ctx: ChannelHandlerContext) {
        ctx.read()
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        bufferedMessages.add(msg)
    }

    override fun flush(ctx: ChannelHandlerContext) {
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if(evt is RequestCompletedEvent) {
            for(msg in bufferedMessages) ctx.write(msg)
            ctx.flush()
            ctx.pipeline().remove(this)
        }
    }
}