package net.woggioni.gbcs.api;

import net.woggioni.gbcs.api.event.RequestEvent;

import java.util.concurrent.CompletableFuture;

public interface CallHandle<T> {
    void postEvent(RequestEvent evt);
    CompletableFuture<T> call();
}
