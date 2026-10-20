package com.spendwitz.statement;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Parses the date formats Indian banks use. Day-first unless the statement says otherwise. */
final class StatementDates {

    private static final List<DateTimeFormatter> FORMATS = Stream.of(
                    "yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "dd/MM/yy", "dd-MM-yy", "dd.MM.yy",
                    "d/M/yyyy", "d-M-yyyy", "d/M/yy", "dd-MMM-yyyy", "dd MMM yyyy", "dd-MMM-yy", "dd MMM yy",
                    "d MMM yyyy", "d-MMM-yyyy", "dd/MMM/yyyy", "MMM d, yyyy", "MMM dd, yyyy", "dd MMMM yyyy")
            .map(StatementDates::formatter)
            .toList();

    private StatementDates() {
    }

    /** Returns null when the value isn't a date (header, footer or balance rows). */
    static LocalDate parse(String raw, String patternHint) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // drop a time part: "19/10/2026 14:32:05", "2026-10-19T10:00"
        String value = raw.trim().replaceFirst("[T ]\\d{1,2}:\\d{2}.*$", "").trim();
        List<DateTimeFormatter> candidates = new ArrayList<>();
        if (patternHint != null && !patternHint.isBlank()) {
            try {
                candidates.add(formatter(patternHint.trim()));
            } catch (IllegalArgumentException ignored) {
                // the model suggested an invalid pattern; fall back to the known ones
            }
        }
        candidates.addAll(FORMATS);
        for (DateTimeFormatter format : candidates) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        return null;
    }

    private static DateTimeFormatter formatter(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH);
    }
}
