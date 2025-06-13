package net.woggioni.rbcs.server.exception

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.DecoderException
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.timeout.ReadTimeoutException
import io.netty.handler.timeout.WriteTimeoutException
import java.net.ConnectException
import java.net.SocketException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import net.woggioni.rbcs.api.exception.CacheException
import net.woggioni.rbcs.api.exception.ContentTooLargeException
import net.woggioni.rbcs.common.contextLogger
import net.woggioni.rbcs.common.debug
import net.woggioni.rbcs.common.log
import org.slf4j.event.Level
import org.slf4j.spi.LoggingEventBuilder

@Sharable
object ExceptionHandler : ChannelDuplexHandler() {

    val NAME : String = this::class.java.name

    private val log = contextLogger()

    private val NOT_AUTHORIZED: FullHttpResponse = DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN, Unpooled.EMPTY_BUFFER
    ).apply {
        headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
    }

    private val NOT_AVAILABLE: FullHttpResponse = DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1, HttpResponseStatus.SERVICE_UNAVAILABLE, Unpooled.EMPTY_BUFFER
    ).apply {
        headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
    }

    private val SERVER_ERROR: FullHttpResponse = DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR, Unpooled.EMPTY_BUFFER
    ).apply {
        headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
    }

    private val TOO_BIG: FullHttpResponse = DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, Unpooled.EMPTY_BUFFER
    ).apply {
        headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        when (cause) {
            is DecoderException -> {
                log.debug(cause.message, cause)
                ctx.close()
            }

            is ConnectException -> {
                log.error(cause.message, cause)
                ctx.writeAndFlush(SERVER_ERROR.retainedDuplicate())
            }

            is SocketException -> {
                log.debug(cause.message, cause)
                ctx.close()
            }

            is SSLPeerUnverifiedException -> {
                ctx.writeAndFlush(NOT_AUTHORIZED.retainedDuplicate())
                    .addListener(ChannelFutureListener.CLOSE_ON_FAILURE)
            }

            is SSLException -> {
                log.debug(cause.message, cause)
                ctx.close()
            }

            is ContentTooLargeException -> {
                log.log(Level.DEBUG, ctx.channel()) { builder : LoggingEventBuilder ->
                    builder.setMessage("Request body is too large")
                }
                ctx.writeAndFlush(TOO_BIG.retainedDuplicate())
                    .addListener(ChannelFutureListener.CLOSE_ON_FAILURE)
            }

            is ReadTimeoutException -> {
                log.debug {
                    val channelId = ctx.channel().id().asShortText()
                    "Read timeout on channel $channelId, closing the connection"
                }
                ctx.close()
            }

            is WriteTimeoutException -> {
                log.debug {
                    val channelId = ctx.channel().id().asShortText()
                    "Write timeout on channel $channelId, closing the connection"
                }
                ctx.close()
            }

            is CacheException -> {
                log.error(cause.message, cause)
                ctx.writeAndFlush(NOT_AVAILABLE.retainedDuplicate())
                    .addListener(ChannelFutureListener.CLOSE_ON_FAILURE)
            }

            else -> {
                log.error(cause.message, cause)
                ctx.writeAndFlush(SERVER_ERROR.retainedDuplicate())
                    .addListener(ChannelFutureListener.CLOSE_ON_FAILURE)
            }
        }
    }
}