package net.woggioni.gbcs.api;

import net.woggioni.gbcs.api.exception.ContentTooLargeException;

import java.nio.channels.ReadableByteChannel;
import java.util.concurrent.CompletableFuture;


public interface Cache extends AutoCloseable {
    CompletableFuture<CallHandle<Void>> get(String key, ResponseEventListener responseEventListener);
    CompletableFuture<CallHandle<Void>> put(String key) throws ContentTooLargeException;
}
