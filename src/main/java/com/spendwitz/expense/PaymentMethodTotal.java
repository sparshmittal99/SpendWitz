package com.spendwitz.expense;

import java.math.BigDecimal;

import com.spendwitz.common.PaymentMethod;

public record PaymentMethodTotal(PaymentMethod paymentMethod, BigDecimal total, long count) {
}
