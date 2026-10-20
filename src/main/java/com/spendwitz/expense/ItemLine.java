package com.spendwitz.expense;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One bought item and the bill it was on, for "how much did I spend on momos". price is the line price. */
public record ItemLine(LocalDate date, String merchant, String item, BigDecimal quantity, BigDecimal price) {
}
