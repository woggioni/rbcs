package net.woggioni.gbcs

import java.net.InetSocketAddress
import java.net.URL
import java.net.URLStreamHandlerFactory
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.AbstractMap.SimpleEntry
import java.util.Arrays
import java.util.Base64
import java.util.concurrent.Executors
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.ChannelPromise
import io.netty.channel.DefaultFileRegion
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.DecoderException
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
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.stream.ChunkedNioFile
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.util.concurrent.DefaultEventExecutorGroup
import io.netty.util.concurrent.DefaultThreadFactory
import io.netty.util.concurrent.EventExecutorGroup
import net.woggioni.gbcs.url.ClasspathUrlStreamHandlerFactoryProvider
import javax.net.ssl.SSLPeerUnverifiedException
import net.woggioni.jwo.Application
import net.woggioni.jwo.JWO
import net.woggioni.jwo.Tuple2
import java.net.URI
import java.util.concurrent.ForkJoinPool


class GradleBuildCacheServer(private val cfg : Configuration) {

    internal class HttpChunkContentCompressor(threshold : Int, vararg  compressionOptions: CompressionOptions = emptyArray())
        : HttpContentCompressor(threshold, *compressionOptions) {
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

    private class ServerInitializer(private val cfg : Configuration) : ChannelInitializer<Channel>() {

        private fun createSslCtx(tlsConfiguration : TlsConfiguration) : SslContext {
            val keyStore = tlsConfiguration.keyStore
            return if(keyStore == null) {
                throw IllegalArgumentException("No keystore configured")
            } else {
                val javaKeyStore = loadKeystore(keyStore.file, keyStore.password)
                val serverKey = javaKeyStore.getKey(
                    keyStore.keyAlias, keyStore.keyPassword?.let(String::toCharArray)) as PrivateKey
                val serverCert : Array<X509Certificate> = Arrays.stream(javaKeyStore.getCertificateChain(keyStore.keyAlias))
                    .map { it as X509Certificate }
                    .toArray {size -> Array<X509Certificate?>(size) { null } }
                SslContextBuilder.forServer(serverKey, *serverCert).apply {
                    if(tlsConfiguration.verifyClients) {
                        clientAuth(ClientAuth.REQUIRE)
                        val trustStore = tlsConfiguration.trustStore
                        if (trustStore != null) {
                            val ts = loadKeystore(trustStore.file, trustStore.password)
                            trustManager(
                                ClientCertificateValidator.getTrustManager(ts, trustStore.checkCertificateStatus))
                        }
                    }
                }.build()
            }
        }

        private val sslContext : SslContext? = cfg.tlsConfiguration?.let(this::createSslCtx)

        companion object {
            val group: EventExecutorGroup = DefaultEventExecutorGroup(Runtime.getRuntime().availableProcessors())
            fun loadKeystore(file : Path, password : String?) : KeyStore {
                val ext = JWO.splitExtension(file)
                    .map(Tuple2<String, String>::get_2)
                    .orElseThrow {
                        IllegalArgumentException(
                            "Keystore file '${file}' must have .jks, .p12, .pfx extension")
                    }
                val keystore = when(ext.substring(1).lowercase()) {
                    "jks" -> KeyStore.getInstance("JKS")
                    "p12", "pfx" -> KeyStore.getInstance("PKCS12")
                    else -> throw IllegalArgumentException(
                        "Keystore file '${file}' must have .jks, .p12, .pfx extension")
                }
                Files.newInputStream(file).use {
                    keystore.load(it, password?.let(String::toCharArray))
                }
                return keystore
            }
        }

        override fun initChannel(ch: Channel) {
            val userAuthorizer = UserAuthorizer(cfg.users)
            val pipeline = ch.pipeline()
            if(sslContext != null) {
                val sslHandler = sslContext.newHandler(ch.alloc())
                pipeline.addLast(sslHandler)
            }
            pipeline.addLast(HttpServerCodec())
            pipeline.addLast(HttpChunkContentCompressor(1024))
            pipeline.addLast(ChunkedWriteHandler())
            pipeline.addLast(HttpObjectAggregator(Int.MAX_VALUE))
//            pipeline.addLast(NettyHttpBasicAuthenticator(mapOf("user" to "password")), userAuthorizer)
            pipeline.addLast(group, ServerHandler(cfg.cacheFolder, cfg.serverPath))
            pipeline.addLast(ExceptionHandler())
            Files.createDirectories(cfg.cacheFolder)
        }
    }

    private class ExceptionHandler : ChannelDuplexHandler() {
        private val log = contextLogger()

        private val NOT_AUTHORIZED: FullHttpResponse = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN, Unpooled.EMPTY_BUFFER).apply {
            headers()[HttpHeaderNames.CONTENT_LENGTH] = "0"
        }
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            when(cause) {
                is DecoderException -> {
                    log.error(cause.message, cause)
                    ctx.close()
                }
                is SSLPeerUnverifiedException -> {
                    ctx.writeAndFlush(NOT_AUTHORIZED.retainedDuplicate()).addListener(ChannelFutureListener.CLOSE_ON_FAILURE)
                }
                else -> {
                     log.error(cause.message, cause)
                     ctx.close()
                 }
            }
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
                    val tmpFile = Files.createTempFile(cacheDir, null, ".tmp")
                    try {
                        Files.newOutputStream(tmpFile).use {
                            content.readBytes(it, content.readableBytes())
                        }
                        Files.move(tmpFile, file, StandardCopyOption.ATOMIC_MOVE)
                    } catch (t : Throwable) {
                        Files.delete(tmpFile)
                        throw t
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

    class ServerHandle(
        private val httpChannel : ChannelFuture,
        private val bossGroup: EventLoopGroup,
        private val workerGroup: EventLoopGroup) : AutoCloseable {

        private val closeFuture : ChannelFuture = httpChannel.channel().closeFuture()

        fun shutdown() : ChannelFuture {
            return httpChannel.channel().close()
        }

        override fun close() {
            try {
                closeFuture.sync()
            } finally {
                val fut1 = workerGroup.shutdownGracefully()
                val fut2 = if(bossGroup !== workerGroup) {
                    bossGroup.shutdownGracefully()
                } else null
                fut1.sync()
                fut2?.sync()
            }
        }
    }

    fun run() : ServerHandle {
        // Create the multithreaded event loops for the server
        val bossGroup = NioEventLoopGroup()
        val serverSocketChannel = NioServerSocketChannel::class.java
        val workerGroup = if(cfg.useVirtualThread) {
            NioEventLoopGroup(0, Executors.newVirtualThreadPerTaskExecutor())
        } else {
            NioEventLoopGroup(0, Executors.newWorkStealingPool())
        }
        // A helper class that simplifies server configuration
        val bootstrap = ServerBootstrap().apply {
            // Configure the server
            group(bossGroup, workerGroup)
            channel(serverSocketChannel)
            childHandler(ServerInitializer(cfg))
            option(ChannelOption.SO_BACKLOG, 128)
            childOption(ChannelOption.SO_KEEPALIVE, true)
        }


        // Bind and start to accept incoming connections.
        val bindAddress = InetSocketAddress(cfg.host, cfg.port)
        val httpChannel = bootstrap.bind(bindAddress).sync()
        return ServerHandle(httpChannel, bossGroup, workerGroup)
    }

    companion object {

        private val log by lazy {
            contextLogger()
        }
        private const val PROTOCOL_HANDLER = "java.protocol.handler.pkgs"
        private const val HANDLERS_PACKAGE = "net.woggioni.gbcs.url"

        /**
         * Reset any cached handlers just in case a jar protocol has already been used. We
         * reset the handler by trying to set a null [URLStreamHandlerFactory] which
         * should have no effect other than clearing the handlers cache.
         */
        private fun resetCachedUrlHandlers() {
            try {
                URL.setURLStreamHandlerFactory(null)
            } catch (ex: Error) {
                // Ignore
            }
        }
        fun registerUrlProtocolHandler() {
            val handlers = System.getProperty(PROTOCOL_HANDLER, "")
            System.setProperty(
                PROTOCOL_HANDLER,
                if (handlers == null || handlers.isEmpty()) HANDLERS_PACKAGE else "$handlers|$HANDLERS_PACKAGE"
            )
            resetCachedUrlHandlers()
        }

        fun loadConfiguration(args: Array<String>) : Configuration {
            registerUrlProtocolHandler()
            URL.setURLStreamHandlerFactory(ClasspathUrlStreamHandlerFactoryProvider())
            Thread.currentThread().contextClassLoader = GradleBuildCacheServer::class.java.classLoader
            val app = Application.builder("gbcs")
                .configurationDirectoryEnvVar("GBCS_CONFIGURATION_DIR")
                .configurationDirectoryPropertyKey("net.woggioni.gbcs.conf.dir")
                .build()
            val confDir = app.computeConfigurationDirectory()
            val configurationFile = confDir.resolve("gbcs.xml")
            log.info { "here" }
            if(!Files.exists(configurationFile)) {
                Files.createDirectories(confDir)
                val defaultConfigurationFileResourcePath = "classpath:net/woggioni/gbcs/gbcs-default.xml"
                val defaultConfigurationFileResource = URI(defaultConfigurationFileResourcePath).toURL()
                Files.newOutputStream(configurationFile).use { outputStream ->
                    defaultConfigurationFileResource.openStream().use { inputStream ->
                        JWO.copy(inputStream, outputStream)
                    }
                }
            }
//            val schemaUrl = javaClass.getResource("/net/woggioni/gbcs/gbcs.xsd")
            val schemaUrl = URL.of(URI("classpath:net/woggioni/gbcs/gbcs.xsd"), null)
            val dbf = Xml.newDocumentBuilderFactory()
//            dbf.schema = Xml.getSchema(this::class.java.module.getResourceAsStream("/net/woggioni/gbcs/gbcs.xsd"))
            dbf.schema = Xml.getSchema(schemaUrl)
            val db = dbf.newDocumentBuilder().apply {
                setErrorHandler(Xml.ErrorHandler(schemaUrl))
            }
            val doc = Files.newInputStream(configurationFile).use(db::parse)
            return Configuration.parse(doc.documentElement)
        }

        @JvmStatic
        fun main(args: Array<String>) {
            val configuration = loadConfiguration(args)
//            Runtime.getRuntime().addShutdownHook(Thread {
//                    Thread.sleep(5000)
//                    javaClass.classLoader.loadClass("net.woggioni.jwo.exception.ChildProcessException")
//                }
//            )
            GradleBuildCacheServer(configuration).run().use {
            }
        }

        fun digest(data : ByteArray,
                   md : MessageDigest = MessageDigest.getInstance("MD5")) : ByteArray {
            md.update(data)
            return md.digest()
        }

        fun digestString(data : ByteArray,
                   md : MessageDigest = MessageDigest.getInstance("MD5")) : String {
            return JWO.bytesToHex(digest(data, md))
        }
    }
}

object GraalNativeImageConfiguration {
    @JvmStatic
    fun main(args: Array<String>) {
        val conf = GradleBuildCacheServer.loadConfiguration(args)
        val handle  = GradleBuildCacheServer(conf).run()
        Thread.sleep(10_000)
        handle.shutdown()
    }
}