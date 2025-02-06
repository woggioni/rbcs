package net.woggioni.rbcs.api.exception;

public class ContentTooLargeException extends RbcsException {
    public ContentTooLargeException(String message, Throwable cause) {
        super(message, cause);
    }
}
