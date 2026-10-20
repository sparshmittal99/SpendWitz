package com.spendwitz.expense;

import java.util.Locale;

/**
 * A bill's own number (invoice / receipt / order number) or a UPI transaction id, cleaned up so the same number
 * matches however it was read: "INV-0042 " and "inv 0042" are both INV0042.
 */
public final class BillReference {

    private static final int MAX_LENGTH = 100;

    private BillReference() {
    }

    /** Returns null for anything too short or without a digit (a table number or "N/A" isn't a bill number). */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (cleaned.length() < 4 || cleaned.length() > MAX_LENGTH || !cleaned.matches(".*\\d.*")) {
            return null;
        }
        return cleaned;
    }

    /** UPI transaction ids (UTR) are 12 digits, and bank statement narrations include them. */
    public static boolean isUpiTransactionId(String reference) {
        return reference != null && reference.matches("\\d{12}");
    }
}
