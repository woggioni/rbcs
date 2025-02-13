package net.woggioni.rbcs.server.handler

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.DefaultFileRegion
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObject
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.LastHttpContent
import net.woggioni.rbcs.api.Cache
import net.woggioni.rbcs.api.RequestHandle
import net.woggioni.rbcs.api.ResponseHandle
import net.woggioni.rbcs.api.event.RequestStreamingEvent
import net.woggioni.rbcs.api.event.ResponseStreamingEvent
import net.woggioni.rbcs.common.contextLogger
import net.woggioni.rbcs.common.debug
import net.woggioni.rbcs.server.debug
import net.woggioni.rbcs.server.warn
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class ServerHandler(private val cache: Cache, private val serverPrefix: Path) :
    SimpleChannelInboundHandler<HttpObject>() {

    private val log = contextLogger()

    override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpObject) {
        when(msg) {
            is HttpRequest -> handleRequest(ctx, msg)
            is HttpContent -> handleContent(msg)
        }
    }

    private var requestHandle : CompletableFuture<RequestHandle?> = CompletableFuture.completedFuture(null)

    private fun handleContent(content : HttpContent) {
            content.retain()
            requestHandle.thenAccept { handle ->
                handle?.let {
                    val evt = if(content is LastHttpContent) {
                        RequestStreamingEvent.LastChunkReceived(content.content())

                    } else {
                        RequestStreamingEvent.ChunkReceived(content.content())
                    }
                    it.handleEvent(evt)
                    content.release()
                } ?: content.release()
            }
        }


    private fun handleRequest(ctx : ChannelHandlerContext, msg : HttpRequest) {
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
                val responseHandle = ResponseHandle { evt ->
                    when (evt) {
                        is ResponseStreamingEvent.ResponseReceived -> {
                            val response = DefaultHttpResponse(msg.protocolVersion(), HttpResponseStatus.OK)
                            response.headers()[HttpHeaderNames.CONTENT_TYPE] = HttpHeaderValues.APPLICATION_OCTET_STREAM
                            if (!keepAlive) {
                                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
                            } else {
                                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
                            }
                            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
                            ctx.writeAndFlush(response)
                        }

                        is ResponseStreamingEvent.LastChunkReceived -> {
                            val channelFuture = ctx.writeAndFlush(DefaultLastHttpContent(evt.chunk))
                            if (!keepAlive) {
                                channelFuture
                                    .addListener(ChannelFutureListener.CLOSE)
                            }
                        }

                        is ResponseStreamingEvent.ChunkReceived -> {
                            ctx.writeAndFlush(DefaultHttpContent(evt.chunk))
                        }

                        is ResponseStreamingEvent.ExceptionCaught -> {
                            ctx.fireExceptionCaught(evt.exception)
                        }

                        is ResponseStreamingEvent.NotFound -> {
                            val response = DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.NOT_FOUND)
                            response.headers()[HttpHeaderNames.CONTENT_LENGTH] = 0
                            ctx.writeAndFlush(response)
                        }

                        is ResponseStreamingEvent.FileReceived -> {
                            val content = DefaultFileRegion(evt.file, 0, evt.file.size())
                            if (keepAlive) {
                                ctx.write(content)
                                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT.retainedDuplicate())
                            } else {
                                ctx.writeAndFlush(content)
                                    .addListener(ChannelFutureListener.CLOSE)
                            }
                        }
                    }
                }
                cache.get(key, responseHandle, ctx.alloc())
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
                val responseHandle = ResponseHandle { evt ->
                    when (evt) {
                        is ResponseStreamingEvent.ResponseReceived -> {
                            val response = DefaultFullHttpResponse(
                                msg.protocolVersion(), HttpResponseStatus.CREATED,
                                Unpooled.copiedBuffer(key.toByteArray())
                            )
                            response.headers()[HttpHeaderNames.CONTENT_LENGTH] = response.content().readableBytes()
                            ctx.writeAndFlush(response)
                            this.requestHandle = CompletableFuture.completedFuture(null)
                        }
                        is ResponseStreamingEvent.ChunkReceived -> {
                            evt.chunk.release()
                        }
                        is ResponseStreamingEvent.ExceptionCaught -> {
                            ctx.fireExceptionCaught(evt.exception)
                        }
                        else -> {}
                    }
                }

                this.requestHandle = cache.put(key, responseHandle, ctx.alloc()).exceptionally { ex ->
                    ctx.fireExceptionCaught(ex)
                    null
                }.also {
                    log.debug { "Replacing request handle with $it"}
                }
            } else {
                log.warn(ctx) {
                    "Got request for unhandled path '${msg.uri()}'"
                }
                val response = DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.BAD_REQUEST)
                response.headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
                ctx.writeAndFlush(response)
            }
        } else if (method == HttpMethod.TRACE) {
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
            this.requestHandle = CompletableFuture.completedFuture(RequestHandle { evt ->
                when(evt) {
                    is RequestStreamingEvent.LastChunkReceived -> {
                        ctx.writeAndFlush(DefaultLastHttpContent(evt.chunk.retain()))
                        this.requestHandle = CompletableFuture.completedFuture(null)
                    }
                    is RequestStreamingEvent.ChunkReceived -> ctx.writeAndFlush(DefaultHttpContent(evt.chunk.retain()))
                    is RequestStreamingEvent.ExceptionCaught -> ctx.fireExceptionCaught(evt.exception)
                    else -> {

                    }
                }
            }).also {
                log.debug { "Replacing request handle with $it"}
            }
            val response = DefaultHttpResponse(msg.protocolVersion(), HttpResponseStatus.OK)
            response.headers().apply {
                set(HttpHeaderNames.CONTENT_TYPE, "message/http")
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

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        requestHandle.thenAccept { handle ->
            handle?.handleEvent(RequestStreamingEvent.ExceptionCaught(cause))
        }
        super.exceptionCaught(ctx, cause)
    }
}