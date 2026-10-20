package com.spendwitz.statement;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One outgoing transaction from a statement. {@code verified} is false when the amount could not be confirmed
 * against the source text (e.g. a scanned PDF), so the review screen asks the user to check it.
 */
public record StatementLine(LocalDate date, String description, BigDecimal amount, boolean verified) {
}
