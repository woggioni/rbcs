package net.woggioni.gbcs.client

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelPipeline
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.DecoderException
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpContentDecompressor
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.stream.ChunkedWriteHandler
import net.woggioni.gbcs.base.Xml
import net.woggioni.gbcs.client.impl.Parser
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.CompletableFuture
import io.netty.util.concurrent.Future as NettyFuture


class GbcsClient(private val profile: Configuration.Profile) : AutoCloseable {
    private val group: NioEventLoopGroup
    private var sslContext: SslContext


    data class Configuration(
        val profiles : Map<String, Profile>
    ) {
        sealed class Authentication {
            data class TlsClientAuthenticationCredentials(val key: PrivateKey, val certificateChain: Array<X509Certificate>) : Authentication()
            data class BasicAuthenticationCredentials(val username: String, val password: String) : Authentication()
        }

        data class Profile(
            val serverURI: URI,
            val authentication : Authentication?
        )

        companion object {
            fun parse(path : Path) : Configuration {
                return Files.newInputStream(path).use {
                    Xml.parseXml(path.toUri().toURL(), it)
                }.let(Parser::parse)
            }
        }
    }

    init {
        group = NioEventLoopGroup()

        this.sslContext = SslContextBuilder.forClient().also { builder ->

            (profile.authentication as? Configuration.Authentication.TlsClientAuthenticationCredentials)?.let { tlsClientAuthenticationCredentials ->
                builder.keyManager(
                    tlsClientAuthenticationCredentials.key,
                    *tlsClientAuthenticationCredentials.certificateChain
                )
            }
        }.build()
    }

    fun get(key: String): CompletableFuture<ByteArray?> {
        return sendRequest(profile.serverURI.resolve(key), HttpMethod.GET, null)
            .thenApply {
                val status = it.status()
                if (it.status() == HttpResponseStatus.NOT_FOUND) {
                    null
                } else if (it.status() != HttpResponseStatus.OK) {
                    throw HttpException(status)
                } else {
                    it.content()
                }
            }.thenApply { maybeByteBuf ->
                maybeByteBuf?.let {
                    val result = ByteArray(it.readableBytes())
                    it.getBytes(0, result)
                    result
                }
            }
    }

    fun put(key: String, content: ByteArray): CompletableFuture<Unit> {
        return sendRequest(profile.serverURI.resolve(key), HttpMethod.PUT, content).thenApply {
            val status = it.status()
            if (it.status() != HttpResponseStatus.CREATED) {
                throw HttpException(status)
            }
        }
    }

    private fun sendRequest(uri: URI, method: HttpMethod, body: ByteArray?): CompletableFuture<FullHttpResponse> {
        val responseFuture = CompletableFuture<FullHttpResponse>()

        try {
            val scheme = if (uri.scheme == null) "http" else uri.scheme
            val host = uri.host
            var port = uri.port
            if (port == -1) {
                port = if ("https".equals(scheme, ignoreCase = true)) 443 else 80
            }

            val bootstrap = Bootstrap()
            bootstrap.group(group)
                .channel(NioSocketChannel::class.java)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        val pipeline: ChannelPipeline = ch.pipeline()


                        // Add SSL handler if needed
                        if ("https".equals(scheme, ignoreCase = true)) {
                            pipeline.addLast("ssl", sslContext.newHandler(ch.alloc(), host, port))
                        }

                        // HTTP handlers
                        pipeline.addLast("codec", HttpClientCodec())
                        pipeline.addLast("decompressor", HttpContentDecompressor())
                        pipeline.addLast("aggregator", HttpObjectAggregator(1048576))
                        pipeline.addLast("chunked", ChunkedWriteHandler())


                        // Custom handler for processing responses
                        pipeline.addLast("handler", object : SimpleChannelInboundHandler<FullHttpResponse>() {
                            override fun channelRead0(
                                ctx: ChannelHandlerContext,
                                response: FullHttpResponse
                            ) {
                                responseFuture.complete(response)
                                ctx.close()
                            }

                            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                                val ex = when (cause) {
                                    is DecoderException -> cause.cause
                                    else -> cause
                                }
                                responseFuture.completeExceptionally(ex)
                                ctx.close()
                            }
                        })
                    }
                })

            // Connect to host
            val channel: Channel = bootstrap.connect(host, port).sync().channel()

            // Prepare the HTTP request
            val request: FullHttpRequest = let {
                val content: ByteBuf? = body?.takeIf(ByteArray::isNotEmpty)?.let(Unpooled::wrappedBuffer)
                DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri.rawPath, content ?: Unpooled.buffer(0)).apply {
                    headers().apply {
                        if (content != null) {
                            set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_OCTET_STREAM)
                            set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
                        }
                        set(HttpHeaderNames.HOST, host)
                        set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
                        set(
                            HttpHeaderNames.ACCEPT_ENCODING,
                            HttpHeaderValues.GZIP.toString() + "," + HttpHeaderValues.DEFLATE.toString()
                        )
                        // Add basic auth if configured
                        (profile.authentication as? Configuration.Authentication.BasicAuthenticationCredentials)?.let { credentials ->
                            val auth = "${credentials.username}:${credentials.password}"
                            val encodedAuth = Base64.getEncoder().encodeToString(auth.toByteArray())
                            set(HttpHeaderNames.AUTHORIZATION, "Basic $encodedAuth")
                        }
                    }
                }
            }

            // Set headers
            // Send the request
            channel.writeAndFlush(request)
        } catch (e: Exception) {
            responseFuture.completeExceptionally(e)
        }

        return responseFuture
    }

    fun shutDown(): NettyFuture<*> {
        return group.shutdownGracefully()
    }

    override fun close() {
        shutDown().sync()
    }
}