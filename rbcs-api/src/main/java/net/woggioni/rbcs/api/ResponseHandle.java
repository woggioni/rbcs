package net.woggioni.rbcs.api;

import net.woggioni.rbcs.api.event.ResponseStreamingEvent;

@FunctionalInterface
public interface ResponseHandle {
    void handleEvent(ResponseStreamingEvent evt);
}
