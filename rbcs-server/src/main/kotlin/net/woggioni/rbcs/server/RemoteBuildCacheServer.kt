package net.woggioni.rbcs.server

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelFactory
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.ChannelPromise
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramChannel
import io.netty.channel.socket.ServerSocketChannel
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.compression.CompressionOptions
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.HttpContentCompressor
import io.netty.handler.codec.http.HttpDecoderConfig
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslHandler
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.AttributeKey
import io.netty.util.concurrent.DefaultEventExecutorGroup
import io.netty.util.concurrent.EventExecutorGroup
import net.woggioni.rbcs.api.AsyncCloseable
import net.woggioni.rbcs.api.Configuration
import net.woggioni.rbcs.api.exception.ConfigurationException
import net.woggioni.rbcs.common.PasswordSecurity.decodePasswordHash
import net.woggioni.rbcs.common.PasswordSecurity.hashPassword
import net.woggioni.rbcs.common.RBCS.getTrustManager
import net.woggioni.rbcs.common.RBCS.loadKeystore
import net.woggioni.rbcs.common.RBCS.toUrl
import net.woggioni.rbcs.common.Xml
import net.woggioni.rbcs.common.createLogger
import net.woggioni.rbcs.common.debug
import net.woggioni.rbcs.common.info
import net.woggioni.rbcs.server.auth.AbstractNettyHttpAuthenticator
import net.woggioni.rbcs.server.auth.Authorizer
import net.woggioni.rbcs.server.auth.RoleAuthorizer
import net.woggioni.rbcs.server.configuration.Parser
import net.woggioni.rbcs.server.configuration.Serializer
import net.woggioni.rbcs.server.exception.ExceptionHandler
import net.woggioni.rbcs.server.handler.MaxRequestSizeHandler
import net.woggioni.rbcs.server.handler.ServerHandler
import net.woggioni.rbcs.server.throttling.BucketManager
import net.woggioni.rbcs.server.throttling.ThrottlingHandler
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Arrays
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.regex.Matcher
import java.util.regex.Pattern
import javax.naming.ldap.LdapName
import javax.net.ssl.SSLPeerUnverifiedException

class RemoteBuildCacheServer(private val cfg: Configuration) {

    companion object {
        private val log = createLogger<RemoteBuildCacheServer>()

        val userAttribute: AttributeKey<Configuration.User> = AttributeKey.valueOf("user")
        val groupAttribute: AttributeKey<Set<Configuration.Group>> = AttributeKey.valueOf("group")

        val DEFAULT_CONFIGURATION_URL by lazy { "jpms://net.woggioni.rbcs.server/net/woggioni/rbcs/server/rbcs-default.xml".toUrl() }
        private const val SSL_HANDLER_NAME = "sslHandler"

        fun loadConfiguration(configurationFile: Path): Configuration {
            val doc = Files.newInputStream(configurationFile).use {
                Xml.parseXml(configurationFile.toUri().toURL(), it)
            }
            return Parser.parse(doc)
        }

        fun dumpConfiguration(conf: Configuration, outputStream: OutputStream) {
            Xml.write(Serializer.serialize(conf), outputStream)
        }
    }

    private class HttpChunkContentCompressor(
        threshold: Int,
        vararg compressionOptions: CompressionOptions = emptyArray()
    ) : HttpContentCompressor(threshold, *compressionOptions) {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            var message: Any? = msg
            if (message is ByteBuf) {
                // convert ByteBuf to HttpContent to make it work with compression. This is needed as we use the
                // ChunkedWriteHandler to send files when compression is enabled.
                val buff = message
                if (buff.isReadable) {
                    // We only encode non empty buffers, as empty buffers can be used for determining when
                    // the content has been flushed and it confuses the HttpContentCompressor
                    // if we let it go
                    message = DefaultHttpContent(buff)
                }
            }
            super.write(ctx, message, promise)
        }
    }

    @Sharable
    private class ClientCertificateAuthenticator(
        authorizer: Authorizer,
        private val anonymousUserGroups: Set<Configuration.Group>?,
        private val userExtractor: Configuration.UserExtractor?,
        private val groupExtractor: Configuration.GroupExtractor?,
    ) : AbstractNettyHttpAuthenticator(authorizer) {

        override fun authenticate(ctx: ChannelHandlerContext, req: HttpRequest): AuthenticationResult? {
            return try {
                val sslHandler = (ctx.pipeline().get(SSL_HANDLER_NAME) as? SslHandler)
                    ?: throw ConfigurationException("Client certificate authentication cannot be used when TLS is disabled")
                val sslEngine = sslHandler.engine()
                sslEngine.session.peerCertificates.takeIf {
                    it.isNotEmpty()
                }?.let { peerCertificates ->
                    val clientCertificate = peerCertificates.first() as X509Certificate
                    val user = userExtractor?.extract(clientCertificate)
                    val group = groupExtractor?.extract(clientCertificate)
                    val allGroups =
                        ((user?.groups ?: emptySet()).asSequence() + sequenceOf(group).filterNotNull()).toSet()
                    AuthenticationResult(user, allGroups)
                } ?: anonymousUserGroups?.let { AuthenticationResult(null, it) }
            } catch (es: SSLPeerUnverifiedException) {
                anonymousUserGroups?.let { AuthenticationResult(null, it) }
            }
        }
    }

    @Sharable
    private class NettyHttpBasicAuthenticator(
        private val users: Map<String, Configuration.User>, authorizer: Authorizer
    ) : AbstractNettyHttpAuthenticator(authorizer) {
        companion object {
            private val log = createLogger<NettyHttpBasicAuthenticator>()
        }

        override fun authenticate(ctx: ChannelHandlerContext, req: HttpRequest): AuthenticationResult? {
            val authorizationHeader = req.headers()[HttpHeaderNames.AUTHORIZATION] ?: let {
                log.debug(ctx) {
                    "Missing Authorization header"
                }
                return users[""]?.let { AuthenticationResult(it, it.groups) }
            }
            val cursor = authorizationHeader.indexOf(' ')
            if (cursor < 0) {
                log.debug(ctx) {
                    "Invalid Authorization header: '$authorizationHeader'"
                }
                return users[""]?.let { AuthenticationResult(it, it.groups) }
            }
            val authenticationType = authorizationHeader.substring(0, cursor)
            if ("Basic" != authenticationType) {
                log.debug(ctx) {
                    "Invalid authentication type header: '$authenticationType'"
                }
                return users[""]?.let { AuthenticationResult(it, it.groups) }
            }
            val (username, password) = Base64.getDecoder().decode(authorizationHeader.substring(cursor + 1))
                .let(::String)
                .let {
                    val colon = it.indexOf(':')
                    if (colon < 0) {
                        log.debug(ctx) {
                            "Missing colon from authentication"
                        }
                        return null
                    }
                    it.substring(0, colon) to it.substring(colon + 1)
                }

            return username.let(users::get)?.takeIf { user ->
                user.password?.let { passwordAndSalt ->
                    val (_, salt) = decodePasswordHash(passwordAndSalt)
                    hashPassword(password, Base64.getEncoder().encodeToString(salt)) == passwordAndSalt
                } ?: false
            }?.let { user ->
                AuthenticationResult(user, user.groups)
            }
        }
    }

    private class ServerInitializer(
        private val cfg: Configuration,
        private val channelFactory : ChannelFactory<SocketChannel>,
        private val datagramChannelFactory : ChannelFactory<DatagramChannel>,
        private val eventExecutorGroup: EventExecutorGroup
    ) : ChannelInitializer<Channel>(), AsyncCloseable {

        companion object {
            private fun createSslCtx(tls: Configuration.Tls): SslContext {
                val keyStore = tls.keyStore
                return if (keyStore == null) {
                    throw IllegalArgumentException("No keystore configured")
                } else {
                    val javaKeyStore = loadKeystore(keyStore.file, keyStore.password)
                    val serverKey = javaKeyStore.getKey(
                        keyStore.keyAlias, (keyStore.keyPassword ?: "").let(String::toCharArray)
                    ) as PrivateKey
                    val serverCert: Array<X509Certificate> =
                        Arrays.stream(javaKeyStore.getCertificateChain(keyStore.keyAlias))
                            .map { it as X509Certificate }
                            .toArray { size -> Array<X509Certificate?>(size) { null } }
                    SslContextBuilder.forServer(serverKey, *serverCert).apply {
                        val clientAuth = tls.trustStore?.let { trustStore ->
                            val ts = loadKeystore(trustStore.file, trustStore.password)
                            trustManager(
                                getTrustManager(ts, trustStore.isCheckCertificateStatus)
                            )
                            if (trustStore.isRequireClientCertificate) ClientAuth.REQUIRE
                            else ClientAuth.OPTIONAL
                        } ?: ClientAuth.NONE
                        clientAuth(clientAuth)
                    }.build()
                }
            }

            private val log = createLogger<ServerInitializer>()
        }

        private val cacheHandlerFactory = cfg.cache.materialize()

        private val bucketManager = BucketManager.from(cfg)

        private val authenticator = when (val auth = cfg.authentication) {
            is Configuration.BasicAuthentication -> NettyHttpBasicAuthenticator(cfg.users, RoleAuthorizer())
            is Configuration.ClientCertificateAuthentication -> {
                ClientCertificateAuthenticator(
                    RoleAuthorizer(),
                    cfg.users[""]?.groups,
                    userExtractor(auth),
                    groupExtractor(auth)
                )
            }

            else -> null
        }

        private val sslContext: SslContext? = cfg.tls?.let(Companion::createSslCtx)

        private fun userExtractor(authentication: Configuration.ClientCertificateAuthentication) =
            authentication.userExtractor?.let { extractor ->
                val pattern = Pattern.compile(extractor.pattern)
                val rdnType = extractor.rdnType
                Configuration.UserExtractor { cert: X509Certificate ->
                    val userName = LdapName(cert.subjectX500Principal.name).rdns.find {
                        it.type == rdnType
                    }?.let {
                        pattern.matcher(it.value.toString())
                    }?.takeIf(Matcher::matches)?.group(1)
                    cfg.users[userName] ?: throw java.lang.RuntimeException("Failed to extract user")
                }
            }

        private fun groupExtractor(authentication: Configuration.ClientCertificateAuthentication) =
            authentication.groupExtractor?.let { extractor ->
                val pattern = Pattern.compile(extractor.pattern)
                val rdnType = extractor.rdnType
                Configuration.GroupExtractor { cert: X509Certificate ->
                    val groupName = LdapName(cert.subjectX500Principal.name).rdns.find {
                        it.type == rdnType
                    }?.let {
                        pattern.matcher(it.value.toString())
                    }?.takeIf(Matcher::matches)?.group(1)
                    cfg.groups[groupName] ?: throw java.lang.RuntimeException("Failed to extract group")
                }
            }

        override fun initChannel(ch: Channel) {
            log.debug {
                "Created connection ${ch.id().asShortText()} with ${ch.remoteAddress()}"
            }
            ch.closeFuture().addListener {
                log.debug {
                    "Closed connection ${ch.id().asShortText()} with ${ch.remoteAddress()}"
                }
            }
            val pipeline = ch.pipeline()
            cfg.connection.also { conn ->
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
            pipeline.addLast(object : ChannelInboundHandlerAdapter() {
                override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                    if (evt is IdleStateEvent) {
                        when (evt.state()) {
                            IdleState.READER_IDLE -> log.debug {
                                "Read timeout reached on channel ${ch.id().asShortText()}, closing the connection"
                            }

                            IdleState.WRITER_IDLE -> log.debug {
                                "Write timeout reached on channel ${ch.id().asShortText()}, closing the connection"
                            }

                            IdleState.ALL_IDLE -> log.debug {
                                "Idle timeout reached on channel ${ch.id().asShortText()}, closing the connection"
                            }

                            null -> throw IllegalStateException("This should never happen")
                        }
                        ctx.close()
                    }
                }
            })
            sslContext?.newHandler(ch.alloc())?.also {
                pipeline.addLast(SSL_HANDLER_NAME, it)
            }
            val httpDecoderConfig = HttpDecoderConfig().apply {
                maxChunkSize = cfg.connection.chunkSize
            }
            pipeline.addLast(HttpServerCodec(httpDecoderConfig))
            pipeline.addLast(MaxRequestSizeHandler.NAME, MaxRequestSizeHandler(cfg.connection.maxRequestSize))
            pipeline.addLast(HttpChunkContentCompressor(1024))
            pipeline.addLast(ChunkedWriteHandler())
            authenticator?.let {
                pipeline.addLast(it)
            }
            pipeline.addLast(ThrottlingHandler(bucketManager, cfg.connection))

            val serverHandler = let {
                val prefix = Path.of("/").resolve(Path.of(cfg.serverPath ?: "/"))
                ServerHandler(prefix) {
                    cacheHandlerFactory.newHandler(cfg, ch.eventLoop(), channelFactory, datagramChannelFactory)
                }
            }
            pipeline.addLast(eventExecutorGroup, ServerHandler.NAME, serverHandler)
            pipeline.addLast(ExceptionHandler.NAME, ExceptionHandler)
        }

        override fun asyncClose() = cacheHandlerFactory.asyncClose()
    }

    class ServerHandle(
        closeFuture: ChannelFuture,
        private val bossGroup: EventExecutorGroup,
        private val executorGroups: Iterable<EventExecutorGroup>,
        private val serverInitializer: AsyncCloseable,
    ) : Future<Void> by from(closeFuture, bossGroup, executorGroups, serverInitializer) {

        companion object {
            private val log = createLogger<ServerHandle>()

            private fun from(
                closeFuture: ChannelFuture,
                bossGroup: EventExecutorGroup,
                executorGroups: Iterable<EventExecutorGroup>,
                serverInitializer: AsyncCloseable
            ): CompletableFuture<Void> {
                val result = CompletableFuture<Void>()
                closeFuture.addListener {
                    val errors = mutableListOf<Throwable>()
                    val deadline = Instant.now().plusSeconds(20)

                    serverInitializer.asyncClose().whenCompleteAsync { _, ex ->
                        if(ex != null) {
                            log.error(ex.message, ex)
                            errors.addLast(ex)
                        }

                        executorGroups.forEach(EventExecutorGroup::shutdownGracefully)
                        bossGroup.terminationFuture().sync()

                        for (executorGroup in executorGroups) {
                            val future = executorGroup.terminationFuture()
                            try {
                                val now = Instant.now()
                                if (now > deadline) {
                                    future.get(0, TimeUnit.SECONDS)
                                } else {
                                    future.get(Duration.between(now, deadline).toMillis(), TimeUnit.MILLISECONDS)
                                }
                            }
                            catch (te: TimeoutException) {
                                errors.addLast(te)
                                log.warn("Timeout while waiting for shutdown of $executorGroup", te)
                            } catch (ex: Throwable) {
                                log.warn(ex.message, ex)
                                errors.addLast(ex)
                            }
                        }

                        if(errors.isEmpty()) {
                            result.complete(null)
                        } else {
                            result.completeExceptionally(errors.first())
                        }
                    }
                }

                return result.thenAccept {
                    log.info {
                        "RemoteBuildCacheServer has been gracefully shut down"
                    }
                }
            }
        }


        fun sendShutdownSignal() {
            bossGroup.shutdownGracefully()
        }
    }

    fun run(): ServerHandle {
        // Create the multithreaded event loops for the server
        val bossGroup = NioEventLoopGroup(1)
        val channelFactory = ChannelFactory<SocketChannel> { NioSocketChannel() }
        val datagramChannelFactory = ChannelFactory<DatagramChannel> { NioDatagramChannel() }
        val serverChannelFactory = ChannelFactory<ServerSocketChannel> { NioServerSocketChannel() }
        val workerGroup = NioEventLoopGroup(0)
        val eventExecutorGroup = run {
            val threadFactory = if (cfg.eventExecutor.isUseVirtualThreads) {
                Thread.ofVirtual().factory()
            } else {
                null
            }
            DefaultEventExecutorGroup(Runtime.getRuntime().availableProcessors(), threadFactory)
        }
        val serverInitializer = ServerInitializer(cfg, channelFactory, datagramChannelFactory, workerGroup)
        val bootstrap = ServerBootstrap().apply {
            // Configure the server
            group(bossGroup, workerGroup)
            channelFactory(serverChannelFactory)
            childHandler(serverInitializer)
            option(ChannelOption.SO_BACKLOG, cfg.incomingConnectionsBacklogSize)
            childOption(ChannelOption.SO_KEEPALIVE, true)
        }


        // Bind and start to accept incoming connections.
        val bindAddress = InetSocketAddress(cfg.host, cfg.port)
        val httpChannel = bootstrap.bind(bindAddress).sync().channel()
        log.info {
            "RemoteBuildCacheServer is listening on ${cfg.host}:${cfg.port}"
        }

        return ServerHandle(
            httpChannel.closeFuture(),
            bossGroup,
            setOf(workerGroup, eventExecutorGroup),
            serverInitializer
        )
    }
}
