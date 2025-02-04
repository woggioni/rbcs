package net.woggioni.gbcs.api.event;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.woggioni.gbcs.api.CallHandle;

sealed public abstract class RequestEvent {
    @Getter
    @RequiredArgsConstructor
    public static final class ChunkSent extends RequestEvent {
        private final ByteBuf chunk;
    }

    @Getter
    @RequiredArgsConstructor
    public static final class LastChunkSent extends RequestEvent {
        private final ByteBuf chunk;
    }
}
