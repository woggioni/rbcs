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
import java.util.Base64
import java.util.ServiceLoader
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
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
import io.netty.handler.codec.compression.CompressionOptions
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
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
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.handler.stream.ChunkedNioFile
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.util.concurrent.DefaultEventExecutorGroup
import io.netty.util.concurrent.EventExecutorGroup
import net.woggioni.jwo.Application
import net.woggioni.jwo.JWO
import net.woggioni.jwo.Tuple2


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

        companion object {
            val group: EventExecutorGroup = DefaultEventExecutorGroup(Runtime.getRuntime().availableProcessors())
            fun loadKeystore(file : Path, password : String?) : KeyStore {
                val ext = JWO.splitExtension(file)
                    .map(Tuple2<String, String>::get_2)
                    .orElseThrow {
                        IllegalArgumentException(
                            "Keystore file '${file}' must have .jks or p12 extension")
                    }
                val keystore = when(ext.lowercase()) {
                    "jks" -> KeyStore.getInstance("JKS")
                    "p12", "pfx" -> KeyStore.getInstance("PKCS12")
                    else -> throw IllegalArgumentException(
                        "Keystore file '${file}' must have .jks or p12 extension")
                }
                Files.newInputStream(file).use {
                    keystore.load(it, password?.let(String::toCharArray))
                }
                return keystore
            }
        }

        override fun initChannel(ch: Channel) {
            val pipeline = ch.pipeline()
            val tlsConfiguration = cfg.tlsConfiguration
            if(tlsConfiguration != null) {
                val ssc = SelfSignedCertificate()
                val keyStore = tlsConfiguration.keyStore
                val sslCtx = if(keyStore == null) {
                    SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build()
                } else {
                    val javaKeyStore = loadKeystore(keyStore.file, keyStore.password)
                    val serverKey = javaKeyStore.getKey(
                        keyStore.keyAlias, keyStore.keyPassword?.let(String::toCharArray)) as PrivateKey
                    val serverCert = javaKeyStore.getCertificateChain(keyStore.keyAlias) as Array<X509Certificate>
                    SslContextBuilder.forServer(serverKey, *serverCert).build()
                }
                val sslHandler = sslCtx.newHandler(ch.alloc())
                pipeline.addLast(sslHandler)
                if(tlsConfiguration.verifyClients) {
                    val trustStore = tlsConfiguration.trustStore?.let {
                        loadKeystore(it.file, it.password)
                    }
                    pipeline.addLast(ClientCertificateValidator.of(sslHandler, trustStore))
                }
            }
            pipeline.addLast(HttpServerCodec())
            pipeline.addLast(HttpChunkContentCompressor(1024))
            pipeline.addLast(ChunkedWriteHandler())
            pipeline.addLast(HttpObjectAggregator(Int.MAX_VALUE))
//            pipeline.addLast(NettyHttpBasicAuthenticator(mapOf("user" to "password")) { user, _ -> user == "user" })
            pipeline.addLast(group, ServerHandler(cfg.cacheFolder, cfg.serverPath))
            pipeline.addLast(ExceptionHandler())
            Files.createDirectories(cfg.cacheFolder)
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
                    .childHandler(ServerInitializer(cfg))
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)

            // Bind and start to accept incoming connections.
            val bindAddress = InetSocketAddress(cfg.host, cfg.port)
            val httpChannel = httpBootstrap.bind(bindAddress).sync()

            // Wait until server socket is closed
            httpChannel.channel().closeFuture().sync()
        } finally {
            workerGroup.shutdownGracefully()
            bossGroup.shutdownGracefully()
        }
    }

    companion object {

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

        @JvmStatic
        fun main(args: Array<String>) {
            SelfSignedCertificate()
            ServiceLoader.load(javaClass.module.layer, URLStreamHandlerFactory::class.java).stream().forEach {
                println(it.type())
            }
//            registerUrlProtocolHandler()
            Thread.currentThread().contextClassLoader = GradleBuildCacheServer::class.java.classLoader
            val app = Application.builder("gbcs")
                .configurationDirectoryEnvVar("GBCS_CONFIGURATION_DIR")
                .configurationDirectoryPropertyKey("net.woggioni.gbcs.conf.dir")
                .build()
            val confDir = app.computeConfigurationDirectory()
            val configurationFile = confDir.resolve("gbcs.xml")

            if(!Files.exists(configurationFile)) {
                Files.createDirectories(confDir)
                val defaultConfigurationFileResourcePath = "net/woggioni/gbcs/gbcs-default.xml"
                val defaultConfigurationFileResource = GradleBuildCacheServer.javaClass.classLoader
                    .getResource(defaultConfigurationFileResourcePath)
                    ?: throw IllegalStateException(
                        "Missing default configuration file 'classpath:$defaultConfigurationFileResourcePath'")
                Files.newOutputStream(configurationFile).use { outputStream ->
                    defaultConfigurationFileResource.openStream().use { inputStream ->
                        JWO.copy(inputStream, outputStream)
                    }
                }
            }
            val schemaResource  = "net/woggioni/gbcs/gbcs.xsd"
            val schemaUrl = URL("classpath:net/woggioni/gbcs/gbcs.xsd")
//            val schemaUrl = GradleBuildCacheServer::class.java.classLoader.getResource(schemaResource)
//                ?: throw IllegalStateException("Missing configuration schema '$schemaResource'")
            val schemaUrl2 = URL(schemaUrl.toString())
            val dbf = Xml.newDocumentBuilderFactory()
            dbf.schema = Xml.getSchema(schemaUrl)
            val doc = Files.newInputStream(configurationFile)
                .use(dbf.newDocumentBuilder()::parse)
            GradleBuildCacheServer(Configuration.parse(doc.documentElement)).run()
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