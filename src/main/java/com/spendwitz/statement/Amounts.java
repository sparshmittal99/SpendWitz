package com.spendwitz.statement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Pattern;

/** Parses statement amounts like "1,23,456.50", "-350", "(350.00)", "12,450.00 Cr", "Rs. 350". */
final class Amounts {

    record Parsed(BigDecimal value, boolean creditMarker, boolean debitMarker) {
    }

    private Amounts() {
    }

    static Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        boolean credit = s.matches(".*CR\\.?$");
        boolean debit = s.matches(".*DR\\.?$");
        s = s.replaceAll("(CR|DR)\\.?$", "").trim();
        boolean negative = s.startsWith("-") || s.endsWith("-") || (s.startsWith("(") && s.endsWith(")"));
        String digits = s.replace("RS.", "").replace("INR", "").replaceAll("[^0-9.]", "");
        if (digits.isEmpty() || digits.chars().filter(c -> c == '.').count() > 1 || digits.equals(".")) {
            return null;
        }
        BigDecimal value = new BigDecimal(digits);
        return new Parsed(negative ? value.negate() : value, credit, debit);
    }

    /** True if the amount is printed in the text, e.g. 350 matches "350.00" or "350", but not "1350" or "350.50". */
    static boolean appearsIn(String text, BigDecimal amount) {
        String plain = text.replace(",", "");
        BigDecimal abs = amount.abs().setScale(2, RoundingMode.HALF_UP);
        String twoDecimals = abs.toPlainString();
        String pattern = Pattern.quote(twoDecimals);
        if (abs.stripTrailingZeros().scale() <= 0) {
            pattern = Pattern.quote(abs.setScale(0, RoundingMode.UNNECESSARY).toPlainString()) + "(?:\\.0{1,2})?";
        }
        return Pattern.compile("(?<![\\d.])" + pattern + "(?![\\d])").matcher(plain).find();
    }
}
