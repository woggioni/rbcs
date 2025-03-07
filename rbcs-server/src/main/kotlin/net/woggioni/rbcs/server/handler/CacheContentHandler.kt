package net.woggioni.rbcs.server.handler

import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandler
import io.netty.channel.ChannelPromise
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.LastHttpContent
import net.woggioni.rbcs.api.message.CacheMessage.CacheValueNotFoundResponse
import net.woggioni.rbcs.api.message.CacheMessage.CacheContent
import net.woggioni.rbcs.api.message.CacheMessage.CachePutResponse
import net.woggioni.rbcs.api.message.CacheMessage.LastCacheContent
import java.net.SocketAddress

class CacheContentHandler(private val pairedHandler : ChannelHandler) : SimpleChannelInboundHandler<HttpContent>(), ChannelOutboundHandler {
    private var requestFinished = false

    override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpContent) {
        if(requestFinished) {
            ctx.fireChannelRead(msg.retain())
        } else {
            when (msg) {
                is LastHttpContent -> {
                    ctx.fireChannelRead(LastCacheContent(msg.content().retain()))
                    requestFinished = true
                }
                else -> ctx.fireChannelRead(CacheContent(msg.content().retain()))
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        super.exceptionCaught(ctx, cause)
    }


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

    override fun flush(ctx: ChannelHandlerContext) {
        ctx.flush()
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        ctx.write(msg, promise)
        if(msg is LastCacheContent || msg is CachePutResponse || msg is CacheValueNotFoundResponse || msg is LastHttpContent) {
            ctx.pipeline().remove(pairedHandler)
            ctx.pipeline().remove(this)
        }
    }
}