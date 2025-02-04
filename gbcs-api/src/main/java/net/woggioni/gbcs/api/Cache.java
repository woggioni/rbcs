package net.woggioni.gbcs.api;

import io.netty.buffer.ByteBuf;
import net.woggioni.gbcs.api.exception.ContentTooLargeException;

import java.nio.channels.ReadableByteChannel;
import java.util.concurrent.CompletableFuture;


public interface Cache extends AutoCloseable {
    CompletableFuture<ReadableByteChannel> get(String key);

    CompletableFuture<Void> put(String key, ByteBuf content) throws ContentTooLargeException;
}
