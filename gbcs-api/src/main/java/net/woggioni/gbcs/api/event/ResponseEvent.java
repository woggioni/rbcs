package net.woggioni.gbcs.api.event;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

sealed public abstract class ResponseEvent {
    @Getter
    @RequiredArgsConstructor
    public final static class ChunkReceived extends ResponseEvent {
        private final ByteBuf chunk;
    }

    public final static class NoContent extends ResponseEvent {
    }

    @Getter
    @RequiredArgsConstructor
    public final static class LastChunkReceived extends ResponseEvent {
        private final ByteBuf chunk;
    }

    @Getter
    @RequiredArgsConstructor
    public final static class ExceptionCaught extends ResponseEvent {
        private final Throwable cause;
    }
}
