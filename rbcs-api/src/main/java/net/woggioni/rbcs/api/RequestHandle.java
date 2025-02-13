package net.woggioni.rbcs.api;

import net.woggioni.rbcs.api.event.RequestStreamingEvent;

@FunctionalInterface
public interface RequestHandle {
    void handleEvent(RequestStreamingEvent evt);
}
