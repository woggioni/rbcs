package net.woggioni.gcs

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.DefaultFileRegion
import io.netty.channel.EventLoopGroup
import io.netty.channel.FileRegion
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.compression.CompressionOptions
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpContentCompressor
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.stream.ChunkedNioFile
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.util.concurrent.DefaultEventExecutorGroup
import io.netty.util.concurrent.EventExecutorGroup
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.AbstractMap.SimpleEntry
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine


class GradleBuildCacheServer {

    internal class HttpChunkContentCompressor(threshold : Int, vararg  compressionOptions: CompressionOptions = emptyArray())
        : HttpContentCompressor(threshold, *compressionOptions) {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            var msg: Any? = msg
            if (msg is ByteBuf) {
                // convert ByteBuf to HttpContent to make it work with compression. This is needed as we use the
                // ChunkedWriteHandler to send files when compression is enabled.
                val buff = msg
                if (buff.isReadable) {
                    // We only encode non empty buffers, as empty buffers can be used for determining when
                    // the content has been flushed and it confuses the HttpContentCompressor
                    // if we let it go
                    msg = DefaultHttpContent(buff)
                }
            }
            super.write(ctx, msg, promise)
        }
    }

    private class NettyHttpBasicAuthenticator(
            private val credentials: Map<String, String>, authorizer: Authorizer) : AbstractNettyHttpAuthenticator(authorizer) {

        companion object {
            private val log = contextLogger()
        }

        override fun authenticate(ctx: ChannelHandlerContext, req: HttpRequest): String? {
            val authorizationHeader = req.headers()[HttpHeaderNames.AUTHORIZATION] ?: let {
                log.debug(ctx) {
                    "Missing Authorization header"
                }
                return null
            }
            val cursor = authorizationHeader.indexOf(' ')
            if (cursor < 0) {
                log.debug(ctx) {
                    "Invalid Authorization header: '$authorizationHeader'"
                }
                return null
            }
            val authenticationType = authorizationHeader.substring(0, cursor)
            if ("Basic" != authenticationType) {
                log.debug(ctx) {
                    "Invalid authentication type header: '$authenticationType'"
                }
                return null
            }
            val (user, password) = Base64.getDecoder().decode(authorizationHeader.substring(cursor + 1))
                    .let(::String)
                    .let {
                        val colon = it.indexOf(':')
                        if(colon < 0) {
                            log.debug(ctx) {
                                "Missing colon from authentication"
                            }
                            return null
                        }
                        it.substring(0, colon) to it.substring(colon + 1)
                    }
            return user.takeIf {
                credentials[user] == password
            }
        }
    }

    private class ServerInitializer(private val cacheDir: Path) : ChannelInitializer<Channel>() {

        override fun initChannel(ch: Channel) {
            val sslEngine: SSLEngine = SSLContext.getDefault().createSSLEngine()
            sslEngine.useClientMode = false
            val pipeline = ch.pipeline()
//            pipeline.addLast(SslHandler(sslEngine))
            pipeline.addLast(HttpServerCodec())
            pipeline.addLast(HttpChunkContentCompressor(1024))
            pipeline.addLast(ChunkedWriteHandler())
            pipeline.addLast(HttpObjectAggregator(Int.MAX_VALUE))
            pipeline.addLast(NettyHttpBasicAuthenticator(mapOf("user" to "password")) { user, _ -> user == "user" })
            pipeline.addLast(group, ServerHandler(cacheDir, "/cache"))
            pipeline.addLast(ExceptionHandler())
            Files.createDirectories(cacheDir)
        }

        companion object {
            val group: EventExecutorGroup = DefaultEventExecutorGroup(Runtime.getRuntime().availableProcessors())
        }
    }

    private class ExceptionHandler : ChannelDuplexHandler() {
        private val log = contextLogger()
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            log.error(cause.message, cause)
            ctx.close()
        }
    }

    private class ServerHandler(private val cacheDir: Path, private val serverPrefix: String) : SimpleChannelInboundHandler<FullHttpRequest>() {

        companion object {
            private val log = contextLogger()

            private fun splitPath(req: HttpRequest): Map.Entry<String, String> {
                val uri = req.uri()
                val i = uri.lastIndexOf('/')
                if (i < 0) throw RuntimeException(String.format("Malformed request URI: '%s'", uri))
                return SimpleEntry(uri.substring(0, i), uri.substring(i + 1))
            }
        }

        override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
            val keepAlive: Boolean = HttpUtil.isKeepAlive(msg)
            val method = msg.method()
            if (method === HttpMethod.GET) {
                val (prefix, key) = splitPath(msg)
                if (serverPrefix == prefix) {
                    val file = cacheDir.resolve(digestString(key.toByteArray()))
                    if (Files.exists(file)) {
                        log.debug(ctx) {
                            "Cache hit for key '$key'"
                        }
                        val response = DefaultHttpResponse(msg.protocolVersion(), HttpResponseStatus.OK)
                        response.headers()[HttpHeaderNames.CONTENT_TYPE] = HttpHeaderValues.APPLICATION_OCTET_STREAM
                        if(!keepAlive) {
                            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
                            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.IDENTITY)
                        } else {
                            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
                            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
                        }
                        ctx.write(response)
                        val channel = FileChannel.open(file, StandardOpenOption.READ)
                        if(keepAlive) {
                            ctx.write(ChunkedNioFile(channel))
                            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                        } else {
                            ctx.writeAndFlush(DefaultFileRegion(channel, 0, Files.size(file))).addListener(ChannelFutureListener.CLOSE)
                        }
                    } else {
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
                val (prefix, key) = splitPath(msg)
                if (serverPrefix == prefix) {
                    log.debug(ctx) {
                        "Added value for key '$key' to build cache"
                    }
                    val content = msg.content()
                    val file = cacheDir.resolve(digestString(key.toByteArray()))
                    Files.newOutputStream(file).use {
                        content.readBytes(it, content.readableBytes())
                    }
                    val response = DefaultFullHttpResponse(msg.protocolVersion(), HttpResponseStatus.CREATED,
                            Unpooled.copiedBuffer(key.toByteArray()))
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

    fun run() {
        // Create the multithreaded event loops for the server
        val bossGroup: EventLoopGroup = NioEventLoopGroup()
        val workerGroup: EventLoopGroup = NioEventLoopGroup()
        try {
            // A helper class that simplifies server configuration
            val httpBootstrap = ServerBootstrap()

            // Configure the server
            httpBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel::class.java)
                    .childHandler(ServerInitializer(Paths.get("/tmp/gbcs"))) // <-- Our handler created here
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)

            // Bind and start to accept incoming connections.
            val httpChannel = httpBootstrap.bind(HTTP_PORT).sync()

            // Wait until server socket is closed
            httpChannel.channel().closeFuture().sync()
        } finally {
            workerGroup.shutdownGracefully()
            bossGroup.shutdownGracefully()
        }
    }

    companion object {
        private const val HTTP_PORT = 8080
        @JvmStatic
        fun main(args: Array<String>) {
            GradleBuildCacheServer().run()
        }

        private val hexArray = "0123456789ABCDEF".toCharArray()

        fun bytesToHex(bytes: ByteArray): String {
            val hexChars = CharArray(bytes.size * 2)
            for (j in bytes.indices) {
                val v: Int = bytes[j].toInt().and(0xFF)
                hexChars[j * 2] = hexArray[v ushr 4]
                hexChars[j * 2 + 1] = hexArray[v and 0x0F]
            }
            return String(hexChars)
        }

        fun digest(data : ByteArray,
                   md : MessageDigest = MessageDigest.getInstance("MD5")) : ByteArray {
            md.update(data)
            return md.digest()
        }

        fun digestString(data : ByteArray,
                   md : MessageDigest = MessageDigest.getInstance("MD5")) : String {
            return bytesToHex(digest(data, md))
        }
    }
}