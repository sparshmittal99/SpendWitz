package com.spendwitz.expense;

import java.math.BigDecimal;

public record MerchantTotal(String merchant, BigDecimal total, long count) {
}
