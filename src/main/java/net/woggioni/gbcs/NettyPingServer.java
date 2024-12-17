package net.woggioni.gbcs;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;

public class NettyPingServer {
    private final int port;

    public NettyPingServer(int port) {
        this.port = port;
    }

    public void start() throws Exception {
        // Create event loop groups for handling incoming connections and processing
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            // Create server bootstrap configuration
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(
                                    new StringDecoder(CharsetUtil.UTF_8),
                                    new StringEncoder(CharsetUtil.UTF_8),
                                    new PingServerHandler()
                            );
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            // Bind and start the server
            ChannelFuture future = bootstrap.bind(port).sync();
            System.out.println("Ping Server started on port: " + port);
            try(final var handle = new GradleBuildCacheServer.ServerHandle(future, bossGroup, workerGroup)) {
                Thread.sleep(5000);
                future.channel().close();
                // Wait until the server socket is closed
                future.channel().closeFuture().sync();
            }
        } finally {
            // Shutdown event loop groups
//            workerGroup.shutdownGracefully();
//            bossGroup.shutdownGracefully();
        }
    }

    // Custom handler for processing ping requests
    private static class PingServerHandler extends SimpleChannelInboundHandler<String> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
            // Check if the received message is a ping request
            if ("ping".equalsIgnoreCase(msg.trim())) {
                // Respond with "pong"
                ctx.writeAndFlush("pong\n");
                System.out.println("Received ping, sent pong");
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // Log and close the connection in case of any errors
            cause.printStackTrace();
            ctx.close();
        }
    }

    // Main method to start the server
    public static void main(String[] args) throws Exception {
        int port = 8080; // Default port
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        new NettyPingServer(port).start();
    }
}

