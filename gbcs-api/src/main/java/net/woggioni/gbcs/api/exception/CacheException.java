package net.woggioni.gbcs.api.exception;

public class CacheException extends GbcsException {
    public CacheException(String message, Throwable cause) {
        super(message, cause);
    }

    public CacheException(String message) {
        this(message, null);
    }
}
