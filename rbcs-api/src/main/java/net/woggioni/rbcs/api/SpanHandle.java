package net.woggioni.rbcs.api;

import org.jetbrains.annotations.NotNull;

public interface SpanHandle {

    void setAttribute(@NotNull String key, @NotNull String value);

    void setAttribute(@NotNull String key, long value);

    void setAttribute(@NotNull String key, boolean value);

}
