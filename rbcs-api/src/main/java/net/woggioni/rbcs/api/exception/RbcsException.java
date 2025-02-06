package net.woggioni.rbcs.api.exception;

public class RbcsException extends RuntimeException {
    public RbcsException(String message, Throwable cause) {
        super(message, cause);
    }
}
