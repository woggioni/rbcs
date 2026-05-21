package net.woggioni.rbcs.api;

import io.netty.channel.ChannelHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public interface TelemetryController {
    void initialize();
    @NotNull ChannelHandler createHandler();

    @Nullable SpanHandle startSpan(@NotNull String command);

    void endSpan(@Nullable SpanHandle span);

    void endSpan(@Nullable SpanHandle span, @NotNull Throwable error);
}
