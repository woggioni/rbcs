package net.woggioni.rbcs.api;

import org.jetbrains.annotations.NotNull;

public interface RedisSpan {

    void setAttribute(@NotNull String key, @NotNull String value);

    void setAttribute(@NotNull String key, long value);
}
