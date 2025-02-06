package net.woggioni.rbcs.server.auth

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
import net.woggioni.rbcs.api.Configuration
import net.woggioni.rbcs.api.Configuration.Group
import net.woggioni.rbcs.api.Role
import net.woggioni.rbcs.server.RemoteBuildCacheServer


abstract class AbstractNettyHttpAuthenticator(private val authorizer: Authorizer) : ChannelInboundHandlerAdapter() {

    companion object {
        private val AUTHENTICATION_FAILED: FullHttpResponse = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED, Unpooled.EMPTY_BUFFER
        ).apply {
            headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
        }

        private val NOT_AUTHORIZED: FullHttpResponse = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN, Unpooled.EMPTY_BUFFER
        ).apply {
            headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
        }
    }

    class AuthenticationResult(val user: Configuration.User?, val groups: Set<Group>)

    abstract fun authenticate(ctx: ChannelHandlerContext, req: HttpRequest): AuthenticationResult?

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is HttpRequest) {
            val result = authenticate(ctx, msg) ?: return authenticationFailure(ctx, msg)
            ctx.channel().attr(RemoteBuildCacheServer.userAttribute).set(result.user)
            ctx.channel().attr(RemoteBuildCacheServer.groupAttribute).set(result.groups)

            val roles = (
                (result.user?.let { user ->
                    user.groups.asSequence().flatMap { group ->
                        group.roles.asSequence()
                    }
                } ?: emptySequence<Role>()) +
                        result.groups.asSequence().flatMap { it.roles.asSequence() }
            ).toSet()
            val authorized = authorizer.authorize(roles, msg)
            if (authorized) {
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