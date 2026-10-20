package com.spendsense.common;

/** The user (or the whole app) used up today's AI allowance. */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
