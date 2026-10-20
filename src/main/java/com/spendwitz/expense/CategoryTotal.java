package com.spendwitz.expense;

import java.math.BigDecimal;

import com.spendwitz.common.Category;

public record CategoryTotal(Category category, BigDecimal total, long count) {
}
