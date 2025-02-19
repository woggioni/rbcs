package net.woggioni.rbcs.api.message;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.woggioni.rbcs.api.CacheValueMetadata;

public sealed interface CacheMessage {

    @Getter
    @RequiredArgsConstructor
    final class CacheGetRequest implements CacheMessage {
        private final String key;
    }

    abstract sealed class CacheGetResponse implements CacheMessage {
    }

    @Getter
    @RequiredArgsConstructor
    final class CacheValueFoundResponse extends CacheGetResponse {
        private final String key;
        private final CacheValueMetadata metadata;
    }

    final class CacheValueNotFoundResponse extends CacheGetResponse {
    }

    @Getter
    @RequiredArgsConstructor
    final class CachePutRequest implements CacheMessage {
        private final String key;
        private final CacheValueMetadata metadata;
    }

    @Getter
    @RequiredArgsConstructor
    final class CachePutResponse implements CacheMessage {
        private final String key;
    }

    @RequiredArgsConstructor
    non-sealed class CacheContent implements CacheMessage, ByteBufHolder {
        protected final ByteBuf chunk;

        @Override
        public ByteBuf content() {
            return chunk;
        }

        @Override
        public CacheContent copy() {
            return replace(chunk.copy());
        }

        @Override
        public CacheContent duplicate() {
            return new CacheContent(chunk.duplicate());
        }

        @Override
        public CacheContent retainedDuplicate() {
            return new CacheContent(chunk.retainedDuplicate());
        }

        @Override
        public CacheContent replace(ByteBuf content) {
            return new CacheContent(content);
        }

        @Override
        public CacheContent retain() {
            chunk.retain();
            return this;
        }

        @Override
        public CacheContent retain(int increment) {
            chunk.retain(increment);
            return this;
        }

        @Override
        public CacheContent touch() {
            chunk.touch();
            return this;
        }

        @Override
        public CacheContent touch(Object hint) {
            chunk.touch(hint);
            return this;
        }

        @Override
        public int refCnt() {
            return chunk.refCnt();
        }

        @Override
        public boolean release() {
            return chunk.release();
        }

        @Override
        public boolean release(int decrement) {
            return chunk.release(decrement);
        }
    }

    final class LastCacheContent extends CacheContent {
        public LastCacheContent(ByteBuf chunk) {
            super(chunk);
        }

        @Override
        public LastCacheContent copy() {
            return replace(chunk.copy());
        }

        @Override
        public LastCacheContent duplicate() {
            return new LastCacheContent(chunk.duplicate());
        }

        @Override
        public LastCacheContent retainedDuplicate() {
            return new LastCacheContent(chunk.retainedDuplicate());
        }

        @Override
        public LastCacheContent replace(ByteBuf content) {
            return new LastCacheContent(chunk);
        }

        @Override
        public LastCacheContent retain() {
            super.retain();
            return this;
        }

        @Override
        public LastCacheContent retain(int increment) {
            super.retain(increment);
            return this;
        }

        @Override
        public LastCacheContent touch() {
            super.touch();
            return this;
        }

        @Override
        public LastCacheContent touch(Object hint) {
            super.touch(hint);
            return this;
        }
    }
}
