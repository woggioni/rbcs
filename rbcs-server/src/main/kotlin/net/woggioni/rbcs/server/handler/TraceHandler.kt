package net.woggioni.rbcs.server.handler

import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.LastHttpContent
import java.nio.file.Path

@Sharable
object TraceHandler : ChannelInboundHandlerAdapter() {
    val NAME = this::class.java.name
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when(msg) {
            is HttpRequest -> {
                val response = DefaultHttpResponse(msg.protocolVersion(), HttpResponseStatus.OK)
                response.headers().apply {
                    set(HttpHeaderNames.CONTENT_TYPE, "message/http")
                    set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
                }
                ctx.write(response)
                val replayedRequestHead = ctx.alloc().buffer()
                replayedRequestHead.writeCharSequence(
                    "TRACE ${Path.of(msg.uri())} ${msg.protocolVersion().text()}\r\n",
                    Charsets.US_ASCII
                )
                msg.headers().forEach { (key, value) ->
                    replayedRequestHead.apply {
                        writeCharSequence(key, Charsets.US_ASCII)
                        writeCharSequence(": ", Charsets.US_ASCII)
                        writeCharSequence(value, Charsets.UTF_8)
                        writeCharSequence("\r\n", Charsets.US_ASCII)
                    }
                }
                replayedRequestHead.writeCharSequence("\r\n", Charsets.US_ASCII)
                ctx.writeAndFlush(replayedRequestHead)
            }
            is LastHttpContent -> {
                ctx.writeAndFlush(msg)
                ctx.pipeline().remove(this)
            }
            is HttpContent -> ctx.writeAndFlush(msg)
            else -> super.channelRead(ctx, msg)
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
        super.exceptionCaught(ctx, cause)
    }
}