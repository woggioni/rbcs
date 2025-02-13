package net.woggioni.rbcs.api.event;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

public sealed interface RequestStreamingEvent {

    @Getter
    @RequiredArgsConstructor
    non-sealed class ChunkReceived implements RequestStreamingEvent {
        private final ByteBuf chunk;
    }

    final class LastChunkReceived extends ChunkReceived {
        public LastChunkReceived(ByteBuf chunk) {
            super(chunk);
        }
    }

    @Getter
    @RequiredArgsConstructor
    final class ExceptionCaught implements RequestStreamingEvent {
        private final Throwable exception;
    }
}
