package com.spendsense.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads transactions out of a spreadsheet using the column mapping the model identified. Dates and amounts are
 * parsed here in Java, straight from the cells, so for Excel/CSV no amount ever comes from the model.
 */
final class TableExtractor {

    /** Where the transaction table is. debitRule says how to tell money out from money in. */
    record ColumnMapping(Integer headerRow, Integer dateColumn, Integer descriptionColumn, Integer debitColumn,
            Integer creditColumn, Integer amountColumn, Integer typeColumn, String debitRule, String debitMarker,
            String dateFormat) {
    }

    record Result(List<StatementLine> debits, int credits) {
    }

    private TableExtractor() {
    }

    static Result extract(List<List<String>> rows, ColumnMapping m) {
        validate(m);
        String rule = m.debitRule().trim().toUpperCase(Locale.ROOT);
        List<StatementLine> debits = new ArrayList<>();
        int credits = 0;
        for (int r = m.headerRow() + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            LocalDate date = StatementDates.parse(cell(row, m.dateColumn()), m.dateFormat());
            String description = cell(row, m.descriptionColumn()).replaceAll("\\s+", " ").trim();
            if (date == null || description.isEmpty()) {
                continue; // opening balance, totals, page footers...
            }
            BigDecimal debit = switch (rule) {
                case "DEBIT_COLUMN" -> positive(Amounts.parse(cell(row, m.debitColumn())));
                case "NEGATIVE_AMOUNT" -> negativeAsDebit(Amounts.parse(cell(row, m.amountColumn())));
                case "POSITIVE_AMOUNT" -> positiveAsDebit(Amounts.parse(cell(row, m.amountColumn())));
                case "TYPE_COLUMN" -> isDebitType(cell(row, m.typeColumn()), m.debitMarker())
                        ? absolute(Amounts.parse(cell(row, m.amountColumn()))) : null;
                default -> null;
            };
            if (debit != null && debit.signum() > 0) {
                debits.add(new StatementLine(date, description, debit, true));
            } else {
                credits++;
            }
        }
        return new Result(debits, credits);
    }

    private static void validate(ColumnMapping m) {
        boolean ok = m != null && m.headerRow() != null && m.dateColumn() != null && m.descriptionColumn() != null
                && m.debitRule() != null;
        if (ok) {
            String rule = m.debitRule().trim().toUpperCase(Locale.ROOT);
            ok = switch (rule) {
                case "DEBIT_COLUMN" -> m.debitColumn() != null;
                case "NEGATIVE_AMOUNT", "POSITIVE_AMOUNT" -> m.amountColumn() != null;
                case "TYPE_COLUMN" -> m.amountColumn() != null && m.typeColumn() != null;
                default -> false;
            };
        }
        if (!ok) {
            throw new StatementException(
                    "Couldn't find the transaction table in this file. Try the PDF or CSV version of the statement.");
        }
    }

    private static String cell(List<String> row, Integer column) {
        return column == null || column < 0 || column >= row.size() ? "" : row.get(column);
    }

    private static BigDecimal positive(Amounts.Parsed p) {
        return p == null ? null : p.value().abs();
    }

    private static BigDecimal absolute(Amounts.Parsed p) {
        return p == null ? null : p.value().abs();
    }

    private static BigDecimal negativeAsDebit(Amounts.Parsed p) {
        if (p == null) {
            return null;
        }
        if (p.debitMarker()) {
            return p.value().abs();
        }
        return p.value().signum() < 0 && !p.creditMarker() ? p.value().abs() : null;
    }

    /** Credit cards: purchases are positive; payments and refunds are negative or marked "Cr". */
    private static BigDecimal positiveAsDebit(Amounts.Parsed p) {
        if (p == null || p.creditMarker()) {
            return null;
        }
        return p.value().signum() > 0 ? p.value() : null;
    }

    private static boolean isDebitType(String type, String marker) {
        String t = type.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) {
            return false;
        }
        if (marker != null && !marker.isBlank()) {
            return t.startsWith(marker.trim().toUpperCase(Locale.ROOT));
        }
        return t.startsWith("D") || t.equals("WITHDRAWAL");
    }
}
