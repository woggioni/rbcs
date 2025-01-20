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
import io.netty.handler.stream.ChunkedNioFile
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
            val key = path.fileName.toString()
            if (serverPrefix == prefix) {
                cache.get(key)?.let { channel ->
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
                            if (keepAlive) {
                                ctx.write(ChunkedNioFile(channel))
                                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                            } else {
                                ctx.writeAndFlush(DefaultFileRegion(channel, 0, channel.size()))
                                    .addListener(ChannelFutureListener.CLOSE)
                            }
                        }

                        else -> {
                            ctx.write(ChunkedNioStream(channel))
                            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                        }
                    }
                } ?: let {
                    log.debug(ctx) {
                        "Cache miss for key '$key'"
                    }
                    val response = DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.NOT_FOUND)
                    response.headers()[HttpHeaderNames.CONTENT_LENGTH] = 0
                    ctx.writeAndFlush(response)
                }
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
                val bodyBytes = msg.content().run {
                    if (isDirect) {
                        ByteArray(readableBytes()).also {
                            readBytes(it)
                        }
                    } else {
                        array()
                    }
                }
                cache.put(key, bodyBytes)
                val response = DefaultFullHttpResponse(
                    msg.protocolVersion(), HttpResponseStatus.CREATED,
                    Unpooled.copiedBuffer(key.toByteArray())
                )
                response.headers()[HttpHeaderNames.CONTENT_LENGTH] = response.content().readableBytes()
                ctx.writeAndFlush(response)
            } else {
                log.warn(ctx) {
                    "Got request for unhandled path '${msg.uri()}'"
                }
                val response = DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.BAD_REQUEST)
                response.headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
                ctx.writeAndFlush(response)
            }
        } else {
            log.warn(ctx) {
                "Got request with unhandled method '${msg.method().name()}'"
            }
            val response = DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.BAD_REQUEST)
            response.headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
            ctx.writeAndFlush(response)
        }
    }
}