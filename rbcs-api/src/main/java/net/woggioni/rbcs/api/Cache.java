package net.woggioni.rbcs.api;

import io.netty.buffer.ByteBufAllocator;

import java.util.concurrent.CompletableFuture;


public interface Cache extends AutoCloseable {

    default void get(String key, ResponseHandle responseHandle, ByteBufAllocator alloc) {
        throw new UnsupportedOperationException();
    }

    default CompletableFuture<RequestHandle> put(String key, ResponseHandle responseHandle, ByteBufAllocator alloc) {
        throw new UnsupportedOperationException();
    }
}
