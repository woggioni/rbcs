package net.woggioni.gbcs.server.handler

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMessage
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.LastHttpContent
import net.woggioni.gbcs.api.Cache
import net.woggioni.gbcs.api.CallHandle
import net.woggioni.gbcs.api.ResponseEventListener
import net.woggioni.gbcs.api.event.RequestEvent
import net.woggioni.gbcs.api.event.ResponseEvent
import net.woggioni.gbcs.common.contextLogger
import net.woggioni.gbcs.server.debug
import net.woggioni.gbcs.server.warn
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class ServerHandler(private val cache: Cache, private val serverPrefix: Path) :
    SimpleChannelInboundHandler<HttpMessage>() {

    companion object {
        @JvmStatic
        private val log = contextLogger()
    }

    private data class TransientContext(
        var key: String?,
        var callHandle: CompletableFuture<CallHandle<Void>>
    )

    private var transientContext: TransientContext? = null

    override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpMessage) {
        when (msg) {
            is HttpRequest -> {
                handleRequest(ctx, msg)
            }

            is LastHttpContent -> {
                transientContext?.run {
                    callHandle.thenCompose { callHandle ->
                        callHandle.postEvent(RequestEvent.LastChunkSent(msg.content()))
                        callHandle.call()
                    }.thenApply {
                        val response = DefaultFullHttpResponse(
                            msg.protocolVersion(), HttpResponseStatus.CREATED,
                            key?.let(String::toByteArray)
                                ?.let(Unpooled::copiedBuffer)
                        )
//                response.headers()[HttpHeaderNames.CONTENT_LENGTH] = response.content().readableBytes()
                        ctx.writeAndFlush(response)
                    }
                }
            }

            is HttpContent -> {
                transientContext?.run {
                    callHandle = callHandle.thenApply { it ->
                        it.postEvent(RequestEvent.ChunkSent(msg.content()))
                        it
                    }
                }
            }
        }

    }

    private fun handleRequest(ctx: ChannelHandlerContext, msg: HttpRequest) {
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
                cache.get(key, object : ResponseEventListener {
                    var first = false
                    override fun listen(evt: ResponseEvent) {
                        when (evt) {
                            is ResponseEvent.NoContent -> {
                                log.debug(ctx) {
                                    "Cache miss for key '$key'"
                                }
                                val response =
                                    DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.NOT_FOUND)
                                response.headers()[HttpHeaderNames.CONTENT_LENGTH] = 0
                                ctx.writeAndFlush(response)
                            }

                            is ResponseEvent.ChunkReceived, is ResponseEvent.LastChunkReceived -> {
                                if (first) {
                                    first = false
                                    log.debug(ctx) {
                                        "Cache hit for key '$key'"
                                    }
                                    val response = DefaultHttpResponse(msg.protocolVersion(), HttpResponseStatus.OK)
                                    response.headers()[HttpHeaderNames.CONTENT_TYPE] =
                                        HttpHeaderValues.APPLICATION_OCTET_STREAM
                                    if (!keepAlive) {
                                        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
                                        response.headers()
                                            .set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.IDENTITY)
                                    } else {
                                        response.headers()
                                            .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
                                        response.headers()
                                            .set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
                                    }
                                    ctx.write(response)
                                }
                                if (evt is ResponseEvent.LastChunkReceived)
                                    ctx.write(DefaultLastHttpContent(evt.chunk))
                                else if (evt is ResponseEvent.ChunkReceived)
                                    ctx.write(DefaultHttpContent(evt.chunk))
                                ctx.flush()
                            }

                            is ResponseEvent.ExceptionCaught -> {
                                log.error(evt.cause.message, evt.cause)
                                val errorResponse = DefaultFullHttpResponse(
                                    msg.protocolVersion(), HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                    evt.cause.message
                                        ?.let(String::toByteArray)
                                        ?.let(Unpooled::copiedBuffer)
                                )
                                ctx.write(errorResponse)
                            }
                        }
                    }
                }).thenCompose(CallHandle<Void>::call)
            } else {
                log.warn(ctx) {
                    "Got request for unhandled path '${msg.uri()}'"
                }
                val response = DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.BAD_REQUEST)
                response.headers()[HttpHeaderNames.CONTENT_LENGTH] = 0
                ctx.writeAndFlush(response)
                ctx.channel().read()
            }
        } else if (method === HttpMethod.PUT) {
            val path = Path.of(msg.uri())
            val prefix = path.parent
            val key = path.fileName.toString()
            if (serverPrefix == prefix) {
                log.debug(ctx) {
                    "Added value for key '$key' to build cache"
                }
                transientContext = TransientContext(key, cache.put(key))
                val response = DefaultFullHttpResponse(
                    msg.protocolVersion(), HttpResponseStatus.CREATED,
                    Unpooled.copiedBuffer(key.toByteArray())
                )
//                response.headers()[HttpHeaderNames.CONTENT_LENGTH] = response.content().readableBytes()
                ctx.writeAndFlush(response)
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
            val response = DefaultHttpResponse(msg.protocolVersion(), HttpResponseStatus.OK)
            response.headers().apply {
                set(HttpHeaderNames.CONTENT_TYPE, "message/http")
            }
            ctx.write(response)
            ctx.writeAndFlush(DefaultHttpContent(replayedRequestHead))
            val callHandle = object : CallHandle<Void> {
                override fun postEvent(evt: RequestEvent) {
                    when (evt) {
                        is RequestEvent.ChunkSent -> {
                            ctx.writeAndFlush(DefaultHttpContent(evt.chunk))
                        }

                        is RequestEvent.LastChunkSent -> {
                            ctx.writeAndFlush(DefaultLastHttpContent(evt.chunk))
                        }
                    }
                }

                override fun call(): CompletableFuture<Void> {
                    return CompletableFuture.completedFuture(null)
                }
            }
            transientContext = TransientContext(null, CompletableFuture.completedFuture(callHandle))
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