package net.woggioni.gbcs.server.handler

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.DefaultFileRegion
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.stream.ChunkedNioStream
import net.woggioni.gbcs.api.Cache
import net.woggioni.gbcs.common.contextLogger
import net.woggioni.gbcs.server.debug
import net.woggioni.gbcs.server.warn
import java.nio.channels.FileChannel
import java.nio.file.Path

@ChannelHandler.Sharable
class ServerHandler(private val cache: Cache, private val serverPrefix: Path) :
    SimpleChannelInboundHandler<FullHttpRequest>() {

    private val log = contextLogger()

    override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
        val keepAlive: Boolean = HttpUtil.isKeepAlive(msg)
        val method = msg.method()
        if (method === HttpMethod.GET) {
            val path = Path.of(msg.uri())
            val prefix = path.parent
            val key = path.fileName?.toString() ?: let {
                val response = DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.NOT_FOUND)
                response.headers()[HttpHeaderNames.CONTENT_LENGTH] = 0
                ctx.writeAndFlush(response)
                return
            }
            if (serverPrefix == prefix) {
                cache.get(key).thenApply { channel ->
                    if(channel != null) {
                        log.debug(ctx) {
                            "Cache hit for key '$key'"
                        }
                        val response = DefaultHttpResponse(msg.protocolVersion(), HttpResponseStatus.OK)
                        response.headers()[HttpHeaderNames.CONTENT_TYPE] = HttpHeaderValues.APPLICATION_OCTET_STREAM
                        if (!keepAlive) {
                            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
                            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.IDENTITY)
                        } else {
                            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
                            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
                        }
                        ctx.write(response)
                        when (channel) {
                            is FileChannel -> {
                                val content = DefaultFileRegion(channel, 0, channel.size())
                                if (keepAlive) {
                                    ctx.write(content)
                                    ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT.retainedDuplicate())
                                } else {
                                    ctx.writeAndFlush(content)
                                        .addListener(ChannelFutureListener.CLOSE)
                                }
                            }
                            else -> {
                                val content = ChunkedNioStream(channel)
                                if (keepAlive) {
                                    ctx.write(content).addListener {
                                        content.close()
                                    }
                                    ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT.retainedDuplicate())
                                } else {
                                    ctx.writeAndFlush(content)
                                        .addListener(ChannelFutureListener.CLOSE)
                                }
                            }
                        }
                    } else {
                        log.debug(ctx) {
                            "Cache miss for key '$key'"
                        }
                        val response = DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.NOT_FOUND)
                        response.headers()[HttpHeaderNames.CONTENT_LENGTH] = 0
                        ctx.writeAndFlush(response)
                    }
                }.whenComplete { _, ex -> ex?.let(ctx::fireExceptionCaught) }
            } else {
                log.warn(ctx) {
                    "Got request for unhandled path '${msg.uri()}'"
                }
                val response = DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.BAD_REQUEST)
                response.headers()[HttpHeaderNames.CONTENT_LENGTH] = 0
                ctx.writeAndFlush(response)
            }
        } else if (method === HttpMethod.PUT) {
            val path = Path.of(msg.uri())
            val prefix = path.parent
            val key = path.fileName.toString()

            if (serverPrefix == prefix) {
                log.debug(ctx) {
                    "Added value for key '$key' to build cache"
                }
                cache.put(key, msg.content()).thenRun {
                    val response = DefaultFullHttpResponse(
                        msg.protocolVersion(), HttpResponseStatus.CREATED,
                        Unpooled.copiedBuffer(key.toByteArray())
                    )
                    response.headers()[HttpHeaderNames.CONTENT_LENGTH] = response.content().readableBytes()
                    ctx.writeAndFlush(response)
                }.whenComplete { _, ex ->
                    ctx.fireExceptionCaught(ex)
                }
            } else {
                log.warn(ctx) {
                    "Got request for unhandled path '${msg.uri()}'"
                }
                val response = DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.BAD_REQUEST)
                response.headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
                ctx.writeAndFlush(response)
            }
        } else if(method == HttpMethod.TRACE) {
            val replayedRequestHead = ctx.alloc().buffer()
            replayedRequestHead.writeCharSequence("TRACE ${Path.of(msg.uri())} ${msg.protocolVersion().text()}\r\n", Charsets.US_ASCII)
            msg.headers().forEach { (key, value) ->
                replayedRequestHead.apply {
                    writeCharSequence(key, Charsets.US_ASCII)
                    writeCharSequence(": ", Charsets.US_ASCII)
                    writeCharSequence(value, Charsets.UTF_8)
                    writeCharSequence("\r\n", Charsets.US_ASCII)
                }
            }
            replayedRequestHead.writeCharSequence("\r\n", Charsets.US_ASCII)
            val requestBody = msg.content()
            requestBody.retain()
            val responseBody = ctx.alloc().compositeBuffer(2).apply {
                addComponents(true, replayedRequestHead)
                addComponents(true, requestBody)
            }
            val response = DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.OK, responseBody)
            response.headers().apply {
                set(HttpHeaderNames.CONTENT_TYPE, "message/http")
                set(HttpHeaderNames.CONTENT_LENGTH, responseBody.readableBytes())
            }
            ctx.writeAndFlush(response)
        } else {
            log.warn(ctx) {
                "Got request with unhandled method '${msg.method().name()}'"
            }
            val response = DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.METHOD_NOT_ALLOWED)
            response.headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
            ctx.writeAndFlush(response)
        }
    }
}