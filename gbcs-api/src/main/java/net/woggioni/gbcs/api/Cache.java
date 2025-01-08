package net.woggioni.gbcs.api;

import net.woggioni.gbcs.api.exception.ContentTooLargeException;

import java.nio.channels.ReadableByteChannel;

public interface Cache extends AutoCloseable {
    ReadableByteChannel get(String key);

    void put(String key, byte[] content) throws ContentTooLargeException;
}
