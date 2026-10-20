package com.spendsense.common;

import java.util.Map;

public final class Money {

    private static final Map<String, String> SYMBOLS = Map.of("INR", "₹", "USD", "$", "EUR", "€", "GBP", "£");

    private Money() {
    }

    public static String symbol(String currency) {
        return SYMBOLS.getOrDefault(currency, currency + " ");
    }
}
