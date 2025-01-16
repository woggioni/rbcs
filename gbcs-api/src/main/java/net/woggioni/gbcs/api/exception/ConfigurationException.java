package net.woggioni.gbcs.api.exception;

public class ConfigurationException extends GbcsException {
    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ConfigurationException(String message) {
        this(message, null);
    }
}
