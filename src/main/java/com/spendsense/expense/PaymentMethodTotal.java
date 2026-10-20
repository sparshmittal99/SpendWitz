package com.spendsense.expense;

import java.math.BigDecimal;

import com.spendsense.common.PaymentMethod;

public record PaymentMethodTotal(PaymentMethod paymentMethod, BigDecimal total, long count) {
}
