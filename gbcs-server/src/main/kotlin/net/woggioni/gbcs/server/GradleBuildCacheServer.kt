package net.woggioni.gbcs.server

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.ChannelPromise
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.compression.CompressionOptions
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.HttpContentCompressor
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslHandler
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import io.netty.util.AttributeKey
import io.netty.util.concurrent.DefaultEventExecutorGroup
import io.netty.util.concurrent.EventExecutorGroup
import net.woggioni.gbcs.api.Configuration
import net.woggioni.gbcs.api.exception.ConfigurationException
import net.woggioni.gbcs.common.GBCS.toUrl
import net.woggioni.gbcs.common.PasswordSecurity.decodePasswordHash
import net.woggioni.gbcs.common.PasswordSecurity.hashPassword
import net.woggioni.gbcs.common.Xml
import net.woggioni.gbcs.common.contextLogger
import net.woggioni.gbcs.common.debug
import net.woggioni.gbcs.common.info
import net.woggioni.gbcs.server.auth.AbstractNettyHttpAuthenticator
import net.woggioni.gbcs.server.auth.Authorizer
import net.woggioni.gbcs.server.auth.ClientCertificateValidator
import net.woggioni.gbcs.server.auth.RoleAuthorizer
import net.woggioni.gbcs.server.configuration.Parser
import net.woggioni.gbcs.server.configuration.Serializer
import net.woggioni.gbcs.server.exception.ExceptionHandler
import net.woggioni.gbcs.server.handler.ServerHandler
import net.woggioni.gbcs.server.throttling.ThrottlingHandler
import net.woggioni.jwo.JWO
import net.woggioni.jwo.Tuple2
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Arrays
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern
import javax.naming.ldap.LdapName
import javax.net.ssl.SSLPeerUnverifiedException

class GradleBuildCacheServer(private val cfg: Configuration) {
    private val log = contextLogger()

    companion object {

        val userAttribute: AttributeKey<Configuration.User> = AttributeKey.valueOf("user")
        val groupAttribute: AttributeKey<Set<Configuration.Group>> = AttributeKey.valueOf("group")

        val DEFAULT_CONFIGURATION_URL by lazy { "classpath:net/woggioni/gbcs/gbcs-default.xml".toUrl() }
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
                    val allGroups = ((user?.groups ?: emptySet()).asSequence() + sequenceOf(group).filterNotNull()).toSet()
                    AuthenticationResult(user, allGroups)
                } ?: anonymousUserGroups?.let{ AuthenticationResult(null, it) }
            } catch (es: SSLPeerUnverifiedException) {
                anonymousUserGroups?.let{ AuthenticationResult(null, it) }
            }
        }
    }

    @Sharable
    private class NettyHttpBasicAuthenticator(
        private val users: Map<String, Configuration.User>, authorizer: Authorizer
    ) : AbstractNettyHttpAuthenticator(authorizer) {
        private val log = contextLogger()

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
        private val eventExecutorGroup: EventExecutorGroup
    ) : ChannelInitializer<Channel>() {

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
                        if (tls.isVerifyClients) {
                            clientAuth(ClientAuth.OPTIONAL)
                            tls.trustStore?.let { trustStore ->
                                val ts = loadKeystore(trustStore.file, trustStore.password)
                                trustManager(
                                    ClientCertificateValidator.getTrustManager(ts, trustStore.isCheckCertificateStatus)
                                )
                            }
                        }
                    }.build()
                }
            }

            fun loadKeystore(file: Path, password: String?): KeyStore {
                val ext = JWO.splitExtension(file)
                    .map(Tuple2<String, String>::get_2)
                    .orElseThrow {
                        IllegalArgumentException(
                            "Keystore file '${file}' must have .jks, .p12, .pfx extension"
                        )
                    }
                val keystore = when (ext.substring(1).lowercase()) {
                    "jks" -> KeyStore.getInstance("JKS")
                    "p12", "pfx" -> KeyStore.getInstance("PKCS12")
                    else -> throw IllegalArgumentException(
                        "Keystore file '${file}' must have .jks, .p12, .pfx extension"
                    )
                }
                Files.newInputStream(file).use {
                    keystore.load(it, password?.let(String::toCharArray))
                }
                return keystore
            }
        }

        private val log = contextLogger()

        private val serverHandler = let {
            val cacheImplementation = cfg.cache.materialize()
            val prefix = Path.of("/").resolve(Path.of(cfg.serverPath ?: "/"))
            ServerHandler(cacheImplementation, prefix)
        }

        private val exceptionHandler = ExceptionHandler()
        private val throttlingHandler = ThrottlingHandler(cfg)

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
                pipeline.addLast(ReadTimeoutHandler(conn.readTimeout.toMillis(), TimeUnit.MILLISECONDS))
                pipeline.addLast(WriteTimeoutHandler(conn.writeTimeout.toMillis(), TimeUnit.MILLISECONDS))
                pipeline.addLast(IdleStateHandler(false, 0, 0, conn.idleTimeout.toMillis(), TimeUnit.MILLISECONDS))
            }
            pipeline.addLast(object : ChannelInboundHandlerAdapter() {
                override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                    if (evt is IdleStateEvent) {
                        log.debug {
                            "Idle timeout reached on channel ${ch.id().asShortText()}, closing the connection"
                        }
                        ctx.close()
                    }
                }
            })
            sslContext?.newHandler(ch.alloc())?.also {
                pipeline.addLast(SSL_HANDLER_NAME, it)
            }
            pipeline.addLast(HttpServerCodec())
            pipeline.addLast(HttpChunkContentCompressor(1024))
            pipeline.addLast(ChunkedWriteHandler())
            pipeline.addLast(HttpObjectAggregator(cfg.connection.maxRequestSize))
            authenticator?.let {
                pipeline.addLast(it)
            }
            pipeline.addLast(throttlingHandler)
            pipeline.addLast(eventExecutorGroup, serverHandler)
            pipeline.addLast(exceptionHandler)
        }
    }

    class ServerHandle(
        httpChannelFuture: ChannelFuture,
        private val executorGroups: Iterable<EventExecutorGroup>
    ) : AutoCloseable {
        private val httpChannel: Channel = httpChannelFuture.channel()
        private val closeFuture: ChannelFuture = httpChannel.closeFuture()
        private val log = contextLogger()

        fun shutdown(): ChannelFuture {
            return httpChannel.close()
        }

        override fun close() {
            try {
                closeFuture.sync()
            } finally {
                executorGroups.forEach {
                    it.shutdownGracefully().sync()
                }
            }
            log.info {
                "GradleBuildCacheServer has been gracefully shut down"
            }
        }
    }

    fun run(): ServerHandle {
        // Create the multithreaded event loops for the server
        val bossGroup = NioEventLoopGroup(0)
        val serverSocketChannel = NioServerSocketChannel::class.java
        val workerGroup = bossGroup
        val eventExecutorGroup = run {
            val threadFactory = if (cfg.eventExecutor.isUseVirtualThreads) {
                Thread.ofVirtual().factory()
            } else {
                null
            }
            DefaultEventExecutorGroup(Runtime.getRuntime().availableProcessors(), threadFactory)
        }
        // A helper class that simplifies server configuration
        val bootstrap = ServerBootstrap().apply {
            // Configure the server
            group(bossGroup, workerGroup)
            channel(serverSocketChannel)
            childHandler(ServerInitializer(cfg, eventExecutorGroup))
            option(ChannelOption.SO_BACKLOG, cfg.incomingConnectionsBacklogSize)
            childOption(ChannelOption.SO_KEEPALIVE, true)
        }


        // Bind and start to accept incoming connections.
        val bindAddress = InetSocketAddress(cfg.host, cfg.port)
        val httpChannel = bootstrap.bind(bindAddress).sync()
        log.info {
            "GradleBuildCacheServer is listening on ${cfg.host}:${cfg.port}"
        }
        return ServerHandle(httpChannel, setOf(bossGroup, workerGroup, eventExecutorGroup))
    }
}
