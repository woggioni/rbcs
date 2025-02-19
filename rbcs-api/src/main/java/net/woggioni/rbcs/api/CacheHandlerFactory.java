package net.woggioni.rbcs.api;

import io.netty.channel.ChannelHandler;

public interface CacheHandlerFactory extends AutoCloseable {
    ChannelHandler newHandler();
}
