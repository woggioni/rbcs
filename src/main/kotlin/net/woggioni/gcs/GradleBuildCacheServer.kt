package net.woggioni.gcs

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.compression.Brotli
import io.netty.handler.codec.compression.CompressionOptions
import io.netty.handler.codec.compression.StandardCompressionOptions
import io.netty.handler.codec.compression.Zstd
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpContentCompressor
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpRequestDecoder
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import io.netty.util.concurrent.DefaultEventExecutorGroup
import io.netty.util.concurrent.EventExecutorGroup
import org.h2.mvstore.FileStore
import org.h2.mvstore.MVStore
import java.util.AbstractMap.SimpleEntry
import java.util.Base64


class GradleBuildCacheServer {
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

    private class ServerInitializer(private val mvStore: MVStore) : ChannelInitializer<Channel>() {

        override fun initChannel(ch: Channel) {
            val pipeline = ch.pipeline()
            pipeline.addLast(HttpServerCodec())
            pipeline.addLast(HttpObjectAggregator(Int.MAX_VALUE))
            pipeline.addLast(HttpContentCompressor(1024, *emptyArray<CompressionOptions>()))
            pipeline.addLast(NettyHttpBasicAuthenticator(mapOf("user" to "password")) { user, _ -> user == "user" })
            pipeline.addLast(group, ServerHandler(mvStore, "/cache"))
        }

        companion object {
            val group: EventExecutorGroup = DefaultEventExecutorGroup(Runtime.getRuntime().availableProcessors())
        }
    }

    private class ServerHandler(private val mvStore: MVStore, private val serverPrefix: String) : SimpleChannelInboundHandler<FullHttpRequest>() {

        companion object {
            private val log = contextLogger()

            private fun splitPath(req: HttpRequest): Map.Entry<String, String> {
                val uri = req.uri()
                val i = uri.lastIndexOf('/')
                if (i < 0) throw RuntimeException(String.format("Malformed request URI: '%s'", uri))
                return SimpleEntry(uri.substring(0, i), uri.substring(i + 1))
            }
        }

        private val cache: MutableMap<String, ByteArray>

        init {
            cache = mvStore.openMap("buildCache")
        }

        override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
            val method = msg.method()
            val response: FullHttpResponse
            if (method === HttpMethod.GET) {
                val (prefix, key) = splitPath(msg)
                if (serverPrefix == prefix) {
                    val value = cache[key]
                    if (value != null) {
                        log.debug(ctx) {
                            "Cache hit for key '$key'"
                        }
                        val content = Unpooled.copiedBuffer(value)
                        response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
                        response.headers()[HttpHeaderNames.CONTENT_TYPE] = HttpHeaderValues.APPLICATION_OCTET_STREAM
                        response.headers()[HttpHeaderNames.CONTENT_LENGTH] = content.readableBytes()
                    } else {
                        log.debug(ctx) {
                            "Cache miss for key '$key'"
                        }
                        response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND)
                        response.headers()[HttpHeaderNames.CONTENT_LENGTH] = 0
                    }
                } else {
                    log.warn(ctx) {
                        "Got request for unhandled path '${msg.uri()}'"
                    }
                    response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST)
                    response.headers()[HttpHeaderNames.CONTENT_LENGTH] = 0
                }
            } else if (method === HttpMethod.PUT) {
                val (prefix, key) = splitPath(msg)
                if (serverPrefix == prefix) {
                    log.debug(ctx) {
                        "Added value for key '$key' to build cache"
                    }
                    val content = msg.content()
                    val value = ByteArray(content.capacity())
                    content.readBytes(value)
                    cache[key] = value
                    mvStore.commit()
                    response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CREATED,
                            Unpooled.copiedBuffer(key.toByteArray()))
                    response.headers()[HttpHeaderNames.CONTENT_LENGTH] = response.content().readableBytes()
                } else {
                    log.warn(ctx) {
                        "Got request for unhandled path '${msg.uri()}'"
                    }
                    response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST)
                    response.headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
                }
            } else {
                log.warn(ctx) {
                    "Got request with unhandled method '${msg.method().name()}'"
                }
                response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST)
                response.headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
            }
            response.retain()
            ctx.write(response)
            ctx.flush()
        }
    }

    fun run() {
        // Create the multithreaded event loops for the server
        val bossGroup: EventLoopGroup = NioEventLoopGroup()
        val workerGroup: EventLoopGroup = NioEventLoopGroup()
        val mvStore = MVStore.Builder()
                .compress()
                .fileName("/tmp/buildCache.mv")
                .open()
        val initialState = mvStore.commit()
        try {
            // A helper class that simplifies server configuration
            val httpBootstrap = ServerBootstrap()

            // Configure the server
            httpBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel::class.java)
                    .childHandler(ServerInitializer(mvStore)) // <-- Our handler created here
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)

            // Bind and start to accept incoming connections.
            val httpChannel = httpBootstrap.bind(HTTP_PORT).sync()

            // Wait until server socket is closed
            httpChannel.channel().closeFuture().sync()
        } finally {
            mvStore.close()
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
    }
}