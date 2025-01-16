package net.woggioni.gbcs.client

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOption
import io.netty.channel.ChannelPipeline
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.pool.AbstractChannelPoolHandler
import io.netty.channel.pool.ChannelPool
import io.netty.channel.pool.FixedChannelPool
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
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import net.woggioni.gbcs.base.Xml
import net.woggioni.gbcs.base.contextLogger
import net.woggioni.gbcs.base.debug
import net.woggioni.gbcs.base.info
import net.woggioni.gbcs.client.impl.Parser
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import io.netty.util.concurrent.Future as NettyFuture


class GbcsClient(private val profile: Configuration.Profile) : AutoCloseable {
    private val group: NioEventLoopGroup
    private var sslContext: SslContext
    private val log = contextLogger()
    private val pool: ChannelPool

    data class Configuration(
        val profiles: Map<String, Profile>
    ) {
        sealed class Authentication {
            data class TlsClientAuthenticationCredentials(
                val key: PrivateKey,
                val certificateChain: Array<X509Certificate>
            ) : Authentication()

            data class BasicAuthenticationCredentials(val username: String, val password: String) : Authentication()
        }

        data class Profile(
            val serverURI: URI,
            val authentication: Authentication?,
            val maxConnections : Int
        )

        companion object {
            fun parse(path: Path): Configuration {
                return Files.newInputStream(path).use {
                    Xml.parseXml(path.toUri().toURL(), it)
                }.let(Parser::parse)
            }
        }
    }

    init {
        group = NioEventLoopGroup()
        sslContext = SslContextBuilder.forClient().also { builder ->
            (profile.authentication as? Configuration.Authentication.TlsClientAuthenticationCredentials)?.let { tlsClientAuthenticationCredentials ->
                builder.keyManager(
                    tlsClientAuthenticationCredentials.key,
                    *tlsClientAuthenticationCredentials.certificateChain
                )
            }
        }.build()

        val (scheme, host, port) = profile.serverURI.run {
            Triple(
                if (scheme == null) "http" else profile.serverURI.scheme,
                host,
                port.takeIf { it > 0 } ?: if ("https" == scheme.lowercase()) 443 else 80
            )
        }

        val bootstrap = Bootstrap().apply {
            group(group)
            channel(NioSocketChannel::class.java)
            option(ChannelOption.TCP_NODELAY, true)
            option(ChannelOption.SO_KEEPALIVE, true)
            remoteAddress(InetSocketAddress(host, port))
        }
        val channelPoolHandler = object : AbstractChannelPoolHandler() {

            @Volatile
            private var connectionCount = AtomicInteger()

            @Volatile
            private var leaseCount = AtomicInteger()

            override fun channelReleased(ch: Channel) {
                log.debug {
                    "Released lease ${leaseCount.decrementAndGet()}"
                }
            }

            override fun channelAcquired(ch: Channel?) {
                log.debug {
                    "Acquired lease ${leaseCount.getAndIncrement()}"
                }
            }

            override fun channelCreated(ch: Channel) {
                log.debug {
                    "Created connection ${connectionCount.getAndIncrement()}"
                }
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
            }
        }
        pool = FixedChannelPool(bootstrap, channelPoolHandler, profile.maxConnections)
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
        // Custom handler for processing responses
        pool.acquire().addListener(object : GenericFutureListener<NettyFuture<Channel>> {
            override fun operationComplete(channelFuture: Future<Channel>) {
                if (channelFuture.isSuccess) {
                    val channel = channelFuture.now
                    val pipeline = channel.pipeline()
                    channel.pipeline().addLast("handler", object : SimpleChannelInboundHandler<FullHttpResponse>() {
                        override fun channelRead0(
                            ctx: ChannelHandlerContext,
                            response: FullHttpResponse
                        ) {
                            responseFuture.complete(response)
                            pipeline.removeLast()
                            pool.release(channel)
                        }

                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            val ex = when (cause) {
                                is DecoderException -> cause.cause
                                else -> cause
                            }
                            responseFuture.completeExceptionally(ex)
                            ctx.close()
                            pipeline.removeLast()
                            pool.release(channel)
                        }
                    })
                    // Prepare the HTTP request
                    val request: FullHttpRequest = let {
                        val content: ByteBuf? = body?.takeIf(ByteArray::isNotEmpty)?.let(Unpooled::wrappedBuffer)
                        DefaultFullHttpRequest(
                            HttpVersion.HTTP_1_1,
                            method,
                            uri.rawPath,
                            content ?: Unpooled.buffer(0)
                        ).apply {
                            headers().apply {
                                if (content != null) {
                                    set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_OCTET_STREAM)
                                    set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
                                }
                                set(HttpHeaderNames.HOST, profile.serverURI.host)
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
                }
            }
        })
        return responseFuture
    }

    fun shutDown(): NettyFuture<*> {
        return group.shutdownGracefully()
    }

    override fun close() {
        shutDown().sync()
    }
}