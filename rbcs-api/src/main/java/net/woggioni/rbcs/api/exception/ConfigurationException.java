package net.woggioni.rbcs.api.exception;

public class ConfigurationException extends RbcsException {
    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ConfigurationException(String message) {
        this(message, null);
    }
}
