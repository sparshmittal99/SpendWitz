package com.spendsense.expense;

import java.math.BigDecimal;

import com.spendsense.common.Category;

public record CategoryTotal(Category category, BigDecimal total, long count) {
}
