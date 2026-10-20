package com.spendwitz.common;

/** The AI provider failed (rate limit, outage, bad output). Shown to the user as a friendly message. */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public AiUnavailableException(String message) {
        super(message);
    }
}
