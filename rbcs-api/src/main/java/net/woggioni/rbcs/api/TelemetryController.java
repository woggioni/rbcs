package net.woggioni.rbcs.api;

import io.netty.channel.ChannelHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface TelemetryController {
    void initialize();
    @NotNull ChannelHandler createHandler();

    @Nullable RedisSpan startRedisSpan(@NotNull String command, @NotNull String key);

    void endRedisSpan(@Nullable RedisSpan span);

    void endRedisSpan(@Nullable RedisSpan span, @NotNull Throwable error);
}
