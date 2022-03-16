package net.woggioni.gbcs

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.util.ReferenceCountUtil

abstract class AbstractNettyHttpAuthenticator(private val authorizer : Authorizer)
        : ChannelInboundHandlerAdapter() {

    private companion object {
        private val AUTHENTICATION_FAILED: FullHttpResponse = DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED, Unpooled.EMPTY_BUFFER).apply {
            headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
        }

        private val NOT_AUTHORIZED: FullHttpResponse = DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN, Unpooled.EMPTY_BUFFER).apply {
            headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
        }
    }
    abstract fun authenticate(ctx : ChannelHandlerContext, req : HttpRequest) : String?

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if(msg is HttpRequest) {
            val user = authenticate(ctx, msg) ?: return authenticationFailure(ctx, msg)
            val authorized = authorizer.authorize(user, msg)
            if(authorized) {
                super.channelRead(ctx, msg)
            } else {
                authorizationFailure(ctx, msg)
            }
        }
    }

    private fun authenticationFailure(ctx: ChannelHandlerContext, msg: Any) {
        ReferenceCountUtil.release(msg)
        ctx.writeAndFlush(AUTHENTICATION_FAILED.retainedDuplicate()).addListener(ChannelFutureListener.CLOSE_ON_FAILURE)
    }

    private fun authorizationFailure(ctx: ChannelHandlerContext, msg: Any) {
        ReferenceCountUtil.release(msg)
        ctx.writeAndFlush(NOT_AUTHORIZED.retainedDuplicate()).addListener(ChannelFutureListener.CLOSE_ON_FAILURE)
    }

}