package net.woggioni.rbcs.api;

import io.netty.channel.ChannelHandler;
import org.jetbrains.annotations.NotNull;

public interface TelemetryController {
    void initialize();
    @NotNull ChannelHandler createHandler();
}
