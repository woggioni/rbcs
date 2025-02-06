package net.woggioni.rbcs.api.exception;

public class CacheException extends RbcsException {
    public CacheException(String message, Throwable cause) {
        super(message, cause);
    }

    public CacheException(String message) {
        this(message, null);
    }
}
