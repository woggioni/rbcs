package net.woggioni.rbcs.api;

import io.netty.channel.ChannelFactory;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;

public interface CacheHandlerFactory extends AsyncCloseable {
    CacheHandler newHandler(
            Configuration configuration,
            EventLoopGroup eventLoopGroup,
            ChannelFactory<SocketChannel> socketChannelFactory,
            ChannelFactory<DatagramChannel> datagramChannelFactory
    );
}
