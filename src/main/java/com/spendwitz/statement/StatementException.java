package com.spendwitz.statement;

/** A problem with the uploaded statement that the user can fix (wrong password, unsupported file...). */
public class StatementException extends RuntimeException {

    public StatementException(String message) {
        super(message);
    }

    public StatementException(String message, Throwable cause) {
        super(message, cause);
    }
}
