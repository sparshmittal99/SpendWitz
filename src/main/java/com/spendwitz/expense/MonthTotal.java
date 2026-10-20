package com.spendwitz.expense;

import java.math.BigDecimal;

/** Native-query projection: month is "yyyy-MM". */
public interface MonthTotal {

    String getMonth();

    BigDecimal getTotal();
}
