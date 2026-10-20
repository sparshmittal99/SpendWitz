package com.spendwitz.common;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

public enum PaymentMethod {

    UPI("UPI", "bi-phone"),
    CASH("Cash", "bi-cash-coin"),
    CARD("Card", "bi-credit-card"),
    NET_BANKING("Net banking", "bi-bank"),
    OTHER("Other", "bi-wallet2");

    private final String label;
    private final String icon;

    PaymentMethod(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    public String getLabel() { return label; }
    public String getIcon() { return icon; }

    /** Lenient parse for values coming back from the LLM ("upi", "Net Banking", "gpay" ...). */
    public static Optional<PaymentMethod> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase().replaceAll("[^A-Z]+", "_").replaceAll("^_|_$", "");
        return switch (normalized) {
            case "GPAY", "GOOGLE_PAY", "PHONEPE", "PAYTM", "BHIM" -> Optional.of(UPI);
            case "DEBIT_CARD", "CREDIT_CARD" -> Optional.of(CARD);
            default -> Arrays.stream(values()).filter(m -> m.name().equals(normalized)).findFirst();
        };
    }

    public static String promptList() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
