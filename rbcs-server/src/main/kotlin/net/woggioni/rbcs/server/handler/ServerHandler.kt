package net.woggioni.rbcs.server.handler

import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpHeaders
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import net.woggioni.rbcs.api.CacheValueMetadata
import net.woggioni.rbcs.api.message.CacheMessage
import net.woggioni.rbcs.api.message.CacheMessage.CacheContent
import net.woggioni.rbcs.api.message.CacheMessage.CacheGetRequest
import net.woggioni.rbcs.api.message.CacheMessage.CachePutRequest
import net.woggioni.rbcs.api.message.CacheMessage.CachePutResponse
import net.woggioni.rbcs.api.message.CacheMessage.CacheValueFoundResponse
import net.woggioni.rbcs.api.message.CacheMessage.CacheValueNotFoundResponse
import net.woggioni.rbcs.api.message.CacheMessage.LastCacheContent
import net.woggioni.rbcs.common.createLogger
import net.woggioni.rbcs.common.debug
import net.woggioni.rbcs.common.warn
import java.nio.file.Path
import java.util.Locale

class ServerHandler(private val serverPrefix: Path) :
    ChannelDuplexHandler() {

    companion object {
        private val log = createLogger<ServerHandler>()
        val NAME = this::class.java.name
    }

    private var httpVersion = HttpVersion.HTTP_1_1
    private var keepAlive = true

    private fun resetRequestMetadata() {
        httpVersion = HttpVersion.HTTP_1_1
        keepAlive = true
    }

    private fun setRequestMetadata(req: HttpRequest) {
        httpVersion = req.protocolVersion()
        keepAlive = HttpUtil.isKeepAlive(req)
    }

    private fun setKeepAliveHeader(headers: HttpHeaders) {
        if (!keepAlive) {
            headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        } else {
            headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        }
    }


    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is HttpRequest -> handleRequest(ctx, msg)
            else -> super.channelRead(ctx, msg)
        }
    }


    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise?) {
        if (msg is CacheMessage) {
            try {
                when (msg) {
                    is CachePutResponse -> {
                        val response = DefaultFullHttpResponse(httpVersion, HttpResponseStatus.CREATED)
                        val keyBytes = msg.key.toByteArray(Charsets.UTF_8)
                        response.headers().apply {
                            set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN)
                            set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
                        }
                        setKeepAliveHeader(response.headers())
                        ctx.write(response)
                        val buf = ctx.alloc().buffer(keyBytes.size).apply {
                            writeBytes(keyBytes)
                        }
                        ctx.writeAndFlush(DefaultLastHttpContent(buf))
                    }

                    is CacheValueNotFoundResponse -> {
                        val response = DefaultFullHttpResponse(httpVersion, HttpResponseStatus.NOT_FOUND)
                        response.headers()[HttpHeaderNames.CONTENT_LENGTH] = 0
                        setKeepAliveHeader(response.headers())
                        ctx.writeAndFlush(response)
                    }

                    is CacheValueFoundResponse -> {
                        val response = DefaultHttpResponse(httpVersion, HttpResponseStatus.OK)
                        response.headers().apply {
                            set(HttpHeaderNames.CONTENT_TYPE, msg.metadata.mimeType ?: HttpHeaderValues.APPLICATION_OCTET_STREAM)
                            msg.metadata.contentDisposition?.let { contentDisposition ->
                                set(HttpHeaderNames.CONTENT_DISPOSITION, contentDisposition)
                            }
                        }
                        setKeepAliveHeader(response.headers())
                        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
                        ctx.writeAndFlush(response)
                    }

                    is LastCacheContent -> {
                        ctx.writeAndFlush(DefaultLastHttpContent(msg.content()))
                    }

                    is CacheContent -> {
                        ctx.writeAndFlush(DefaultHttpContent(msg.content()))
                    }

                    else -> throw UnsupportedOperationException("This should never happen")
                }.let { channelFuture ->
                    if (promise != null) {
                        channelFuture.addListener {
                            if (it.isSuccess) promise.setSuccess()
                            else promise.setFailure(it.cause())
                        }
                    }
                }
            } finally {
                resetRequestMetadata()
            }
        } else super.write(ctx, msg, promise)
    }


    private fun handleRequest(ctx: ChannelHandlerContext, msg: HttpRequest) {
        setRequestMetadata(msg)
        val method = msg.method()
        if (method === HttpMethod.GET) {
            val path = Path.of(msg.uri()).normalize()
            if (path.startsWith(serverPrefix)) {
                val relativePath = serverPrefix.relativize(path)
                val key = relativePath.toString()
                ctx.pipeline().addAfter(NAME, CacheContentHandler.NAME, CacheContentHandler)
                key.let(::CacheGetRequest)
                    .let(ctx::fireChannelRead)
                    ?: ctx.channel().write(CacheValueNotFoundResponse())
            } else {
                log.warn(ctx) {
                    "Got request for unhandled path '${msg.uri()}'"
                }
                val response = DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.BAD_REQUEST)
                response.headers()[HttpHeaderNames.CONTENT_LENGTH] = 0
                ctx.writeAndFlush(response)
            }
        } else if (method === HttpMethod.PUT) {
            val path = Path.of(msg.uri()).normalize()
            if (path.startsWith(serverPrefix)) {
                val relativePath = serverPrefix.relativize(path)
                val key = relativePath.toString()
                log.debug(ctx) {
                    "Added value for key '$key' to build cache"
                }
                ctx.pipeline().addAfter(NAME, CacheContentHandler.NAME, CacheContentHandler)
                path.fileName?.toString()
                    ?.let {
                        val mimeType = HttpUtil.getMimeType(msg)?.toString()
                        CachePutRequest(key, CacheValueMetadata(msg.headers().get(HttpHeaderNames.CONTENT_DISPOSITION), mimeType))
                    }
                    ?.let(ctx::fireChannelRead)
                    ?: ctx.channel().write(CacheValueNotFoundResponse())
            } else {
                log.warn(ctx) {
                    "Got request for unhandled path '${msg.uri()}'"
                }
                val response = DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.BAD_REQUEST)
                response.headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
                ctx.writeAndFlush(response)
            }
        } else if (method == HttpMethod.TRACE) {
            super.channelRead(ctx, msg)
        } else {
            log.warn(ctx) {
                "Got request with unhandled method '${msg.method().name()}'"
            }
            val response = DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.METHOD_NOT_ALLOWED)
            response.headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
            ctx.writeAndFlush(response)
        }
    }


    data class ContentDisposition(val type: Type?, val fileName: String?) {
        enum class Type {
            attachment, `inline`;

            companion object {
                @JvmStatic
                fun parse(maybeString: String?) = maybeString.let { s ->
                    try {
                        java.lang.Enum.valueOf(Type::class.java, s)
                    } catch (ex: IllegalArgumentException) {
                        null
                    }
                }
            }
        }

        companion object {
            @JvmStatic
            fun parse(contentDisposition: String) : ContentDisposition {
                val parts = contentDisposition.split(";").dropLastWhile { it.isEmpty() }.toTypedArray()
                val dispositionType = parts[0].trim { it <= ' ' }.let(Type::parse)  // Get the type (e.g., attachment)

                var filename: String? = null
                for (i in 1..<parts.size) {
                    val part = parts[i].trim { it <= ' ' }
                    if (part.lowercase(Locale.getDefault()).startsWith("filename=")) {
                        filename = part.substring("filename=".length).trim { it <= ' ' }.replace("\"", "")
                        break
                    }
                }
                return ContentDisposition(dispositionType, filename)
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        super.exceptionCaught(ctx, cause)
    }
}