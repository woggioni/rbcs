package net.woggioni.rbcs.api.event;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.nio.channels.FileChannel;

public sealed interface ResponseStreamingEvent {

    final class ResponseReceived implements ResponseStreamingEvent {
    }

    @Getter
    @RequiredArgsConstructor
    non-sealed class ChunkReceived implements ResponseStreamingEvent {
        private final ByteBuf chunk;
    }

    @Getter
    @RequiredArgsConstructor
    non-sealed class FileReceived implements ResponseStreamingEvent {
        private final FileChannel file;
    }

    final class LastChunkReceived extends ChunkReceived {
        public LastChunkReceived(ByteBuf chunk) {
            super(chunk);
        }
    }

    @Getter
    @RequiredArgsConstructor
    final class ExceptionCaught implements ResponseStreamingEvent {
        private final Throwable exception;
    }

    final class NotFound implements ResponseStreamingEvent { }

    NotFound NOT_FOUND = new NotFound();
    ResponseReceived RESPONSE_RECEIVED = new ResponseReceived();
}
