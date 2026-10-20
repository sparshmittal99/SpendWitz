package com.spendwitz.receipt;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the model must return for an uploaded bill or UPI payment screenshot. Spring AI derives a JSON schema
 * from this record and parses the reply into it. Enum-like fields are kept as strings and validated in Java,
 * so a slightly-off model answer degrades gracefully instead of failing the whole scan.
 */
public record ReceiptData(
        String documentType,
        String merchant,
        String date,
        BigDecimal total,
        BigDecimal tax,
        String category,
        String paymentMethod,
        String paymentStatus,
        String direction,
        String transactionId,
        List<Item> items) {

    public record Item(String name, BigDecimal quantity, BigDecimal price) {
    }
}
