package net.woggioni.gcs;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.compression.StandardCompressionOptions;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import org.h2.mvstore.MVStore;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class GradleBuildCacheServer23 {

//    private static final class NettyHttpBasicAuthenticator extends ChannelInboundHandlerAdapter {
//
//        private static final FullHttpResponse AUTHENTICATION_FAILED = new DefaultFullHttpResponse(
//                HTTP_1_1, HttpResponseStatus.UNAUTHORIZED, Unpooled.EMPTY_BUFFER);
//
//        private final String basicAuthHeader;
//
//        public NettyHttpBasicAuthenticator(String username, String password) {
//            this.basicAuthHeader =
//                    "Basic " + Base64.getEncoder()
//                            .encodeToString((username + ":" + password)
//                                    .getBytes(StandardCharsets.ISO_8859_1));
//        }
//
//        @Override
//        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//            if (msg instanceof HttpRequest) {
//                HttpRequest req = (HttpRequest) msg;
//                String authorizationHeader = req.headers().get(HttpHeaderNames.AUTHORIZATION);
//                log.warn();
//
//                int cursor = authorizationHeader.indexOf(' ');
//                if(cursor < 0) {
//                    if(log.isDebugEnabled()) {
//                        log.debug("Invalid Authorization header: '{}'", authorizationHeader);
//                    }
//                    authenticationFailure(ctx, msg);
//                }
//                String authenticationType = authorizationHeader.substring(0, cursor);
//                if(!Objects.equals("Basic", authenticationType)) {
//                    if(log.isDebugEnabled()) {
//                        ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
//                        log.debug("Invalid authentication type header: '{}'", authenticationType);
//                    }
//                    authenticationFailure(ctx, msg);
//                }
//
//                if (HttpUtil.is100ContinueExpected(req)) {
//                    HttpResponse accept = acceptMessage(req);
//
//                    if (accept == null) {
//                        // the expectation failed so we refuse the request.
//                        HttpResponse rejection = rejectResponse(req);
//                        ReferenceCountUtil.release(msg);
//                        ctx.writeAndFlush(rejection).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
//                        return;
//                    }
//
//                    ctx.writeAndFlush(accept).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
//                    req.headers().remove(HttpHeaderNames.EXPECT);
//                }
//            }
//            super.channelRead(ctx, msg);
//        }
//
//        public void authenticationFailure(ChannelHandlerContext ctx, Object msg) {
//            ReferenceCountUtil.release(msg);
//            ctx.writeAndFlush(AUTHENTICATION_FAILED.retainedDuplicate()).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
//        }
//    }
//
//    @RequiredArgsConstructor
//    private static class ServerInitializer extends ChannelInitializer<Channel> {
//
//        private final MVStore mvStore;
//        static final EventExecutorGroup group =
//            new DefaultEventExecutorGroup(Runtime.getRuntime().availableProcessors());
//
//        @Override
//        protected void initChannel(Channel ch) {
//            ChannelPipeline pipeline = ch.pipeline();
//            pipeline.addLast(new HttpServerCodec());
//            pipeline.addLast(new HttpObjectAggregator(Integer.MAX_VALUE));
//            pipeline.addLast(group, new ServerHandler(mvStore, "/cache"));
//            pipeline.addLast(
//                new HttpContentCompressor(1024,
//                    StandardCompressionOptions.deflate(),
//                    StandardCompressionOptions.brotli(),
//                    StandardCompressionOptions.gzip(),
//                    StandardCompressionOptions.zstd()));
//        }
//    }
//
//    private static  class AuthenticationHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
//        @Override
//        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
//
//        }
//    }
//
//    @Slf4j
//    private static class ServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
//
//        private final String serverPrefix;
//        private final Map<String, byte[]> cache;
//
//        public ServerHandler(MVStore mvStore, String serverPrefix) {
//            this.serverPrefix = serverPrefix;
//            cache = mvStore.openMap("buildCache");
//        }
//
//        private static Map.Entry<String, String> splitPath(HttpRequest req) {
//            String uri = req.uri();
//            int i = uri.lastIndexOf('/');
//            if(i < 0) throw new RuntimeException(String.format("Malformed request URI: '%s'", uri));
//            return new AbstractMap.SimpleEntry<>(uri.substring(0, i), uri.substring(i + 1));
//        }
//
//        private void authenticate(HttpRequest req) {
//            String authorizationHeader = req.headers().get(HttpHeaderNames.AUTHORIZATION);
//            if(authorizationHeader != null) {
//                int cursor = authorizationHeader.indexOf(' ');
//                if(cursor < 0) {
//                    throw new IllegalArgumentException(
//                            String.format("Illegal format for 'Authorization' HTTP header: '%s'", authorizationHeader));
//                }
//                String authorizationType = authorizationHeader.substring(0, cursor);
//                if(!Objects.equals("Basic", authorizationType) {
//
//                }
//            }
//
//        }
//
//        @Override
//        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
//            HttpMethod method = msg.method();
//            FullHttpResponse response;
//            if(method == HttpMethod.GET) {
//                Map.Entry<String, String> prefixAndKey = splitPath(msg);
//                String prefix = prefixAndKey.getKey();
//                String key = prefixAndKey.getValue();
//                if(Objects.equals(serverPrefix, prefix)) {
//                    byte[] value = cache.get(key);
//                    if(value != null) {
//                        if(log.isDebugEnabled()) {
//                            log.debug("Successfully retrieved value for key '{}' from build cache", key);
//                        }
//                        ByteBuf content = Unpooled.copiedBuffer(value);
//                        response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
//                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_OCTET_STREAM);
//                        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
//                    } else {
//                        if(log.isDebugEnabled()) {
//                            log.debug("Cache miss for key '{}'", key);
//                        }
//                        response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
//                    }
//                } else {
//                    if(log.isWarnEnabled()) {
//                        log.warn("Got request for unhandled path '{}'", msg.uri());
//                    }
//                    response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
//                }
//            } else if(method == HttpMethod.PUT) {
//                Map.Entry<String, String> prefixAndKey = splitPath(msg);
//                String prefix = prefixAndKey.getKey();
//                String key = prefixAndKey.getValue();
//                if(Objects.equals(serverPrefix, prefix)) {
//                    if(log.isDebugEnabled()) {
//                        log.debug("Added value for key '{}' to build cache", key);
//                    }
//                    cache.put(key, msg.content().array());
//                    ByteBuf content = Unpooled.copiedBuffer(key, StandardCharsets.UTF_8);
//                    response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CREATED, content);
//                } else {
//                    if(log.isWarnEnabled()) {
//                        log.warn("Got request for unhandled path '{}'", msg.uri());
//                    }
//                    response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
//                }
//            } else {
//                if(log.isWarnEnabled()) {
//                    log.warn("Got request with unhandled method '{}'", msg.method().name());
//                }
//                response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
//            }
//            ctx.write(response);
//            ctx.flush();
//        }
//    }
//
//    private static final int HTTP_PORT = 8080;
//
//    public void run() throws Exception {
//
//        // Create the multithreaded event loops for the server
//        EventLoopGroup bossGroup = new NioEventLoopGroup();
//        EventLoopGroup workerGroup = new NioEventLoopGroup();
//        try(MVStore mvStore = MVStore.open("/tmp/buildCache")) {
//            // A helper class that simplifies server configuration
//            ServerBootstrap httpBootstrap = new ServerBootstrap();
//
//            // Configure the server
//            httpBootstrap.group(bossGroup, workerGroup)
//                .channel(NioServerSocketChannel.class)
//                .childHandler(new ServerInitializer(mvStore)) // <-- Our handler created here
//                .option(ChannelOption.SO_BACKLOG, 128)
//                .childOption(ChannelOption.SO_KEEPALIVE, true);
//
//            // Bind and start to accept incoming connections.
//            ChannelFuture httpChannel = httpBootstrap.bind(HTTP_PORT).sync();
//
//            // Wait until server socket is closed
//            httpChannel.channel().closeFuture().sync();
//        }
//        finally {
//            workerGroup.shutdownGracefully();
//            bossGroup.shutdownGracefully();
//        }
//    }
//
//    public static void main(String[] args) throws Exception {
//        new GradleBuildCacheServer().run();
//    }
}