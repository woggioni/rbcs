package net.woggioni.rbcs.client

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOption
import io.netty.channel.ChannelPipeline
import io.netty.channel.IoEventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioIoHandler
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
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.Future as NettyFuture
import io.netty.util.concurrent.GenericFutureListener
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.random.Random
import net.woggioni.rbcs.api.CacheValueMetadata
import net.woggioni.rbcs.common.RBCS.loadKeystore
import net.woggioni.rbcs.common.createLogger
import net.woggioni.rbcs.common.debug
import net.woggioni.rbcs.common.trace

class RemoteBuildCacheClient(private val profile: Configuration.Profile) : AutoCloseable {
    companion object {
        private val log = createLogger<RemoteBuildCacheClient>()
    }

    private val group: IoEventLoopGroup
    private val sslContext: SslContext
    private val pool: ChannelPool

    init {
        group = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())
        sslContext = SslContextBuilder.forClient().also { builder ->
            (profile.authentication as? Configuration.Authentication.TlsClientAuthenticationCredentials)?.let { tlsClientAuthenticationCredentials ->
                builder.apply {
                    keyManager(
                        tlsClientAuthenticationCredentials.key,
                        *tlsClientAuthenticationCredentials.certificateChain
                    )
                    profile.tlsTruststore?.let { trustStore ->
                        if (!trustStore.verifyServerCertificate) {
                            trustManager(object : X509TrustManager {
                                override fun checkClientTrusted(certChain: Array<out X509Certificate>, p1: String?) {
                                }

                                override fun checkServerTrusted(certChain: Array<out X509Certificate>, p1: String?) {
                                }

                                override fun getAcceptedIssuers() = null
                            })
                        } else {
                            trustStore.file?.let {
                                val ts = loadKeystore(it, trustStore.password)
                                val trustManagerFactory: TrustManagerFactory =
                                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                                trustManagerFactory.init(ts)
                                trustManager(trustManagerFactory)
                            }
                        }
                    }
                }
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
            profile.connectionTimeout?.let {
                option(ChannelOption.CONNECT_TIMEOUT_MILLIS, it.toMillis().toInt())
            }
        }
        val channelPoolHandler = object : AbstractChannelPoolHandler() {

            @Volatile
            private var connectionCount = AtomicInteger()

            @Volatile
            private var leaseCount = AtomicInteger()

            override fun channelReleased(ch: Channel) {
                val activeLeases = leaseCount.decrementAndGet()
                log.trace {
                    "Released channel ${ch.id().asShortText()}, number of active leases: $activeLeases"
                }
            }

            override fun channelAcquired(ch: Channel) {
                val activeLeases = leaseCount.getAndIncrement()
                log.trace {
                    "Acquired channel ${ch.id().asShortText()}, number of active leases: $activeLeases"
                }
            }

            override fun channelCreated(ch: Channel) {
                val connectionId = connectionCount.incrementAndGet()
                log.debug {
                    "Created connection ${ch.id().asShortText()}, total number of active connections: $connectionId"
                }
                ch.closeFuture().addListener {
                    val activeConnections = connectionCount.decrementAndGet()
                    log.debug {
                        "Closed connection ${
                            ch.id().asShortText()
                        }, total number of active connections: $activeConnections"
                    }
                }
                val pipeline: ChannelPipeline = ch.pipeline()

                profile.connection?.also { conn ->
                    val readIdleTimeout = conn.readIdleTimeout.toMillis()
                    val writeIdleTimeout = conn.writeIdleTimeout.toMillis()
                    val idleTimeout = conn.idleTimeout.toMillis()
                    if (readIdleTimeout > 0 || writeIdleTimeout > 0 || idleTimeout > 0) {
                        pipeline.addLast(
                            IdleStateHandler(
                                true,
                                readIdleTimeout,
                                writeIdleTimeout,
                                idleTimeout,
                                TimeUnit.MILLISECONDS
                            )
                        )
                    }
                }

                // Add SSL handler if needed
                if ("https".equals(scheme, ignoreCase = true)) {
                    pipeline.addLast("ssl", sslContext.newHandler(ch.alloc(), host, port))
                }

                // HTTP handlers
                pipeline.addLast("codec", HttpClientCodec())
                if (profile.compressionEnabled) {
                    pipeline.addLast("decompressor", HttpContentDecompressor())
                }
                pipeline.addLast("aggregator", HttpObjectAggregator(134217728))
                pipeline.addLast("chunked", ChunkedWriteHandler())
            }
        }
        pool = FixedChannelPool(bootstrap, channelPoolHandler, profile.maxConnections)
    }

    private fun executeWithRetry(operation: () -> CompletableFuture<FullHttpResponse>): CompletableFuture<FullHttpResponse> {
        val retryPolicy = profile.retryPolicy
        return if (retryPolicy != null) {
            val outcomeHandler = OutcomeHandler<FullHttpResponse> { outcome ->
                when (outcome) {
                    is OperationOutcome.Success -> {
                        val response = outcome.result
                        val status = response.status()
                        when (status) {
                            HttpResponseStatus.TOO_MANY_REQUESTS -> {
                                val retryAfter = response.headers()[HttpHeaderNames.RETRY_AFTER]?.let { headerValue ->
                                    try {
                                        headerValue.toLong() * 1000
                                    } catch (nfe: NumberFormatException) {
                                        null
                                    }
                                }
                                OutcomeHandlerResult.Retry(retryAfter)
                            }

                            HttpResponseStatus.INTERNAL_SERVER_ERROR, HttpResponseStatus.SERVICE_UNAVAILABLE ->
                                OutcomeHandlerResult.Retry()

                            else -> OutcomeHandlerResult.DoNotRetry()
                        }
                    }

                    is OperationOutcome.Failure -> {
                        OutcomeHandlerResult.Retry()
                    }
                }
            }
            executeWithRetry(
                group,
                retryPolicy.maxAttempts,
                retryPolicy.initialDelayMillis.toDouble(),
                retryPolicy.exp,
                outcomeHandler,
                Random.Default,
                operation
            )
        } else {
            operation()
        }
    }

    fun healthCheck(nonce: ByteArray): CompletableFuture<ByteArray?> {
        return executeWithRetry {
            sendRequest(profile.serverURI, HttpMethod.TRACE, nonce)
        }.thenApply {
            val status = it.status()
            if (it.status() != HttpResponseStatus.OK) {
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

    fun get(key: String): CompletableFuture<ByteArray?> {
        return executeWithRetry {
            sendRequest(profile.serverURI.resolve(key), HttpMethod.GET, null)
        }.thenApply {
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

    fun put(key: String, content: ByteArray, metadata: CacheValueMetadata): CompletableFuture<Unit> {
        return executeWithRetry {
            val extraHeaders = sequenceOf(
                metadata.mimeType?.let { HttpHeaderNames.CONTENT_TYPE to it },
                metadata.contentDisposition?.let { HttpHeaderNames.CONTENT_DISPOSITION to it }
            ).filterNotNull()
            sendRequest(profile.serverURI.resolve(key), HttpMethod.PUT, content, extraHeaders.asIterable())
        }.thenApply {
            val status = it.status()
            if (it.status() != HttpResponseStatus.CREATED && it.status() != HttpResponseStatus.OK) {
                throw HttpException(status)
            }
        }
    }

    private fun sendRequest(
        uri: URI,
        method: HttpMethod,
        body: ByteArray?,
        extraHeaders: Iterable<Pair<CharSequence, CharSequence>>? = null
    ): CompletableFuture<FullHttpResponse> {
        val responseFuture = CompletableFuture<FullHttpResponse>()
        // Custom handler for processing responses

        pool.acquire().addListener(object : GenericFutureListener<NettyFuture<Channel>> {

            override fun operationComplete(channelFuture: Future<Channel>) {
                if (channelFuture.isSuccess) {
                    val channel = channelFuture.now
                    val pipeline = channel.pipeline()

                    val closeListener = GenericFutureListener<Future<Void>> {
                        responseFuture.completeExceptionally(IOException("The remote server closed the connection"))
                    }
                    channel.closeFuture().addListener(closeListener)

                    val responseHandler = object : SimpleChannelInboundHandler<FullHttpResponse>() {

                        override fun handlerAdded(ctx: ChannelHandlerContext) {
                            channel.closeFuture().removeListener(closeListener)
                        }

                        override fun channelRead0(
                            ctx: ChannelHandlerContext,
                            response: FullHttpResponse
                        ) {
                            pipeline.remove(this)
                            responseFuture.complete(response)
                            if(!profile.connection.requestPipelining) {
                                pool.release(channel)
                            }
                        }

                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            ctx.newPromise()
                            val ex = when (cause) {
                                is DecoderException -> cause.cause
                                else -> cause
                            }
                            responseFuture.completeExceptionally(ex)
                            ctx.close()
                        }

                        override fun channelInactive(ctx: ChannelHandlerContext) {
                            responseFuture.completeExceptionally(IOException("The remote server closed the connection"))
                            if(!profile.connection.requestPipelining) {
                                pool.release(channel)
                            }
                            super.channelInactive(ctx)
                        }

                        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                            if (evt is IdleStateEvent) {
                                val te = when (evt.state()) {
                                    IdleState.READER_IDLE -> TimeoutException(
                                        "Read timeout",
                                    )

                                    IdleState.WRITER_IDLE -> TimeoutException("Write timeout")

                                    IdleState.ALL_IDLE -> TimeoutException("Idle timeout")
                                    null -> throw IllegalStateException("This should never happen")
                                }
                                responseFuture.completeExceptionally(te)
                                super.userEventTriggered(ctx, evt)
                                if (this === pipeline.last()) {
                                    ctx.close()
                                }
                                if(!profile.connection.requestPipelining) {
                                    pool.release(channel)
                                }
                            } else {
                                super.userEventTriggered(ctx, evt)
                            }
                        }
                    }
                    pipeline.addLast(responseHandler)


                    // Prepare the HTTP request
                    val request: FullHttpRequest = let {
                        val content: ByteBuf? = body?.takeIf(ByteArray::isNotEmpty)?.let(Unpooled::wrappedBuffer)
                        DefaultFullHttpRequest(
                            HttpVersion.HTTP_1_1,
                            method,
                            uri.rawPath,
                            content ?: Unpooled.buffer(0)
                        ).apply {
                            // Set headers
                            headers().apply {
                                if (content != null) {
                                    set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
                                }
                                set(HttpHeaderNames.HOST, profile.serverURI.host)
                                set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
                                if (profile.compressionEnabled) {
                                    set(
                                        HttpHeaderNames.ACCEPT_ENCODING,
                                        HttpHeaderValues.GZIP.toString() + "," + HttpHeaderValues.DEFLATE.toString()
                                    )
                                }
                                extraHeaders?.forEach { (k, v) ->
                                    add(k, v)
                                }
                                // Add basic auth if configured
                                (profile.authentication as? Configuration.Authentication.BasicAuthenticationCredentials)?.let { credentials ->
                                    val auth = "${credentials.username}:${credentials.password}"
                                    val encodedAuth = Base64.getEncoder().encodeToString(auth.toByteArray())
                                    set(HttpHeaderNames.AUTHORIZATION, "Basic $encodedAuth")
                                }
                            }
                        }
                    }

                    // Send the request
                    channel.writeAndFlush(request).addListener {
                        if(!it.isSuccess) {
                            val ex = it.cause()
                            log.warn(ex.message, ex)
                        }
                        if(profile.connection.requestPipelining) {
                            pool.release(channel)
                        }
                    }
                } else {
                    responseFuture.completeExceptionally(channelFuture.cause())
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