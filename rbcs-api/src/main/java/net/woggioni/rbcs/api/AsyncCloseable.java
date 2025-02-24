package net.woggioni.rbcs.api;

import java.util.concurrent.CompletableFuture;

public interface AsyncCloseable extends AutoCloseable {

    CompletableFuture<Void> asyncClose();

    @Override
    default void close() throws Exception {
        asyncClose().get();
    }
}
