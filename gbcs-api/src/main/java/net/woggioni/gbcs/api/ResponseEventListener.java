package net.woggioni.gbcs.api;

import net.woggioni.gbcs.api.event.ResponseEvent;

public interface ResponseEventListener {
    void listen(ResponseEvent evt);
}
