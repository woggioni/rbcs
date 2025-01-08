package net.woggioni.gbcs.api.exception;

public class ContentTooLargeException extends GbcsException {
    public ContentTooLargeException(String message, Throwable cause) {
        super(message, cause);
    }
}
