package com.spendwitz.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spendwitz.cash.CashService;
import com.spendwitz.cash.CashTopUp;
import com.spendwitz.cash.CashTopUpRepository;
import com.spendwitz.common.AiUnavailableException;
import com.spendwitz.common.Category;
import com.spendwitz.common.PaymentMethod;
import com.spendwitz.expense.Expense;
import com.spendwitz.expense.ExpenseForm;
import com.spendwitz.expense.ExpenseRepository;
import com.spendwitz.expense.ExpenseService;
import com.spendwitz.expense.ExpenseSource;
import com.spendwitz.expense.ExpenseSpecs;
import com.spendwitz.user.AuthUser;

/**
 * Bank / credit card statement import:
 * read the file -> find outgoing transactions -> let the model clean them up and classify them ->
 * flag ones already in SpendWitz -> the user reviews -> save expenses and ATM withdrawals.
 */
@Service
public class StatementImportService {

    static final int MAX_TRANSACTIONS = 300;
    private static final int ENRICH_BATCH = 40;
    private static final int PDF_CHUNK_CHARS = 7000;
    private static final Pattern UPI_REF = Pattern.compile("(?<!\\d)(\\d{12})(?!\\d)");
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    public record SkippedLine(LocalDate date, String description, BigDecimal amount, String reason) {
    }

    public record Preview(List<StatementImportForm.Row> rows, List<SkippedLine> skipped, int creditsIgnored) {

        public long duplicates() {
            return rows.stream().filter(r -> r.getDuplicateOf() != null).count();
        }

        public long needsCheck() {
            return rows.stream().filter(StatementImportForm.Row::isNeedsCheck).count();
        }
    }

    public record SaveResult(int expenses, int withdrawals) {
    }

    private final StatementReader reader;
    private final StatementAi ai;
    private final ExpenseRepository expenses;
    private final ExpenseService expenseService;
    private final CashService cashService;
    private final CashTopUpRepository topUps;

    public StatementImportService(StatementReader reader, StatementAi ai, ExpenseRepository expenses,
            ExpenseService expenseService, CashService cashService, CashTopUpRepository topUps) {
        this.reader = reader;
        this.ai = ai;
        this.expenses = expenses;
        this.expenseService = expenseService;
        this.cashService = cashService;
        this.topUps = topUps;
    }

    public record Upload(String fileName, byte[] bytes) {
    }

    /** The combined preview, plus a message for each file that couldn't be read. */
    public record Batch(Preview preview, List<String> failures) {
    }

    /**
     * Reads one or more statements (different accounts, or several months) into one review table. With a single
     * file its error is thrown as before; with several, a file that fails is reported and the rest still import.
     */
    public Batch previewAll(AuthUser user, List<Upload> uploads, String password) {
        List<StatementImportForm.Row> rows = new ArrayList<>();
        List<SkippedLine> skipped = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int credits = 0;
        for (Upload upload : uploads) {
            try {
                Preview preview = read(user, upload.fileName(), upload.bytes(), password);
                markRepeats(rows, preview.rows());
                rows.addAll(preview.rows());
                skipped.addAll(preview.skipped());
                credits += preview.creditsIgnored();
            } catch (StatementException | AiUnavailableException e) {
                if (uploads.size() == 1) {
                    throw e;
                }
                failures.add(upload.fileName() + ": " + e.getMessage());
            }
        }
        if (failures.size() == uploads.size()) {
            throw new StatementException("None of the statements could be read. " + String.join(" ", failures));
        }
        if (rows.size() > MAX_TRANSACTIONS) {
            throw new StatementException("Together these statements have " + rows.size() + " outgoing transactions. "
                    + "Import at most " + MAX_TRANSACTIONS + " at a time.");
        }
        rows.sort(Comparator.comparing(StatementImportForm.Row::getExpenseDate));
        markDuplicates(user.id(), rows.stream().filter(r -> r.getDuplicateOf() == null).toList());
        return new Batch(new Preview(rows, skipped, credits), failures);
    }

    /**
     * Overlapping statements (e.g. 1–31 Oct and 15 Oct–15 Nov of the same account) list the same transaction
     * twice, with the same date, amount and bank narration. Each earlier row can match only one later row.
     */
    static void markRepeats(List<StatementImportForm.Row> earlier, List<StatementImportForm.Row> added) {
        Map<String, Integer> available = new HashMap<>();
        earlier.forEach(r -> available.merge(repeatKey(r), 1, Integer::sum));
        for (StatementImportForm.Row row : added) {
            String key = repeatKey(row);
            if (available.getOrDefault(key, 0) > 0) {
                available.merge(key, -1, Integer::sum);
                markDuplicate(row, "Also in another statement you uploaded");
            }
        }
    }

    private static String repeatKey(StatementImportForm.Row row) {
        return row.getExpenseDate() + "|" + row.getAmount().stripTrailingZeros().toPlainString() + "|" + row.getNote();
    }

    private Preview read(AuthUser user, String fileName, byte[] bytes, String password) {
        StatementReader.Content content = reader.read(fileName, bytes, password);
        List<StatementLine> debits = new ArrayList<>();
        int credits = switch (content) {
            case StatementReader.Table table -> {
                if (table.rows().size() < 2) {
                    throw new StatementException("That file looks empty.");
                }
                var result = TableExtractor.extract(table.rows(), ai.mapColumns(user, table.rows()));
                debits.addAll(result.debits());
                yield result.credits();
            }
            case StatementReader.Pdf pdf -> readPdf(user, pdf, debits);
        };
        if (debits.isEmpty()) {
            throw new StatementException("No outgoing transactions were found in this statement.");
        }
        if (debits.size() > MAX_TRANSACTIONS) {
            throw new StatementException("This statement has " + debits.size() + " outgoing transactions. "
                    + "Import at most " + MAX_TRANSACTIONS + " at a time (one or two months).");
        }
        debits.sort(Comparator.comparing(StatementLine::date));

        Map<Integer, StatementAi.Enrichment> enrichments = new HashMap<>();
        for (int start = 0; start < debits.size(); start += ENRICH_BATCH) {
            var batch = debits.subList(start, Math.min(start + ENRICH_BATCH, debits.size()));
            for (var e : ai.enrich(user, batch, start)) {
                if (e != null && e.index() != null) {
                    enrichments.putIfAbsent(e.index(), e);
                }
            }
        }

        List<StatementImportForm.Row> rows = new ArrayList<>();
        List<SkippedLine> skipped = new ArrayList<>();
        for (int i = 0; i < debits.size(); i++) {
            StatementLine line = debits.get(i);
            StatementAi.Enrichment e = enrichments.get(i);
            String kind = e == null || e.kind() == null ? StatementImportForm.EXPENSE : e.kind().trim().toUpperCase(Locale.ROOT);
            if (kind.equals("SKIP")) {
                skipped.add(new SkippedLine(line.date(), line.description(), line.amount(),
                        e.reason() == null || e.reason().isBlank() ? "Not spending" : e.reason()));
                continue;
            }
            rows.add(toRow(line, e, kind.equals(StatementImportForm.ATM_WITHDRAWAL)));
        }
        return new Preview(rows, skipped, credits);
    }

    private int readPdf(AuthUser user, StatementReader.Pdf pdf, List<StatementLine> debits) {
        int credits = 0;
        if (!pdf.hasText()) {
            // Scanned statement: the vision model reads it, and every amount gets flagged for checking
            for (var t : ai.extractFromScannedPdf(user, pdf.unlockedPdf())) {
                credits += addPdfTransaction(t, null, debits);
            }
            return credits;
        }
        for (String chunk : chunks(pdf.pages())) {
            for (var t : ai.extractFromText(user, chunk)) {
                credits += addPdfTransaction(t, chunk, debits);
            }
        }
        return credits;
    }

    /** Returns 1 if the transaction was a credit. Amounts are checked against the page text they came from. */
    private static int addPdfTransaction(StatementAi.PdfTransaction t, String sourceText, List<StatementLine> debits) {
        if (t == null || t.amount() == null || t.amount().signum() == 0) {
            return 0;
        }
        if (!"DEBIT".equalsIgnoreCase(String.valueOf(t.direction()).trim())) {
            return 1;
        }
        LocalDate date = StatementDates.parse(t.date(), "yyyy-MM-dd");
        String description = t.description() == null ? "" : t.description().replaceAll("\\s+", " ").trim();
        if (date == null || description.isEmpty()) {
            return 0;
        }
        boolean verified = sourceText != null && Amounts.appearsIn(sourceText, t.amount());
        debits.add(new StatementLine(date, description, t.amount().abs(), verified));
        return 0;
    }

    private static List<String> chunks(List<String> pages) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String page : pages) {
            if (current.length() > 0 && current.length() + page.length() > PDF_CHUNK_CHARS) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            current.append(page).append('\n');
        }
        if (!current.toString().isBlank()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private static StatementImportForm.Row toRow(StatementLine line, StatementAi.Enrichment e, boolean atm) {
        StatementImportForm.Row row = new StatementImportForm.Row();
        row.setKind(atm ? StatementImportForm.ATM_WITHDRAWAL : StatementImportForm.EXPENSE);
        String merchant = e == null || e.merchant() == null || e.merchant().isBlank()
                ? line.description() : e.merchant().trim();
        row.setMerchant(atm ? "ATM withdrawal" : merchant.length() > 150 ? merchant.substring(0, 150) : merchant);
        row.setAmount(line.amount());
        row.setExpenseDate(line.date());
        row.setCategory(e == null ? Category.OTHER : Category.parse(e.category()).orElse(Category.OTHER));
        row.setPaymentMethod(e == null ? guessMethod(line.description())
                : PaymentMethod.parse(e.paymentMethod()).orElse(guessMethod(line.description())));
        String note = line.description();
        row.setNote(note.length() > 500 ? note.substring(0, 500) : note);
        row.setNeedsCheck(!line.verified());
        return row;
    }

    private static PaymentMethod guessMethod(String description) {
        return description.toUpperCase(Locale.ROOT).contains("UPI") ? PaymentMethod.UPI : PaymentMethod.OTHER;
    }

    /**
     * An existing expense matches when it has the same UPI reference number, or the same amount within a day.
     * Each existing expense can match only once, so two real ₹20 chai payments aren't both hidden by one.
     */
    void markDuplicates(long userId, List<StatementImportForm.Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        LocalDate from = rows.stream().map(StatementImportForm.Row::getExpenseDate).min(LocalDate::compareTo).orElseThrow().minusDays(1);
        LocalDate to = rows.stream().map(StatementImportForm.Row::getExpenseDate).max(LocalDate::compareTo).orElseThrow().plusDays(1);
        List<Expense> existing = expenses.findAll(ExpenseSpecs.filter(userId, null, null, null, from, to));
        List<CashTopUp> existingTopUps = topUps.findByUserIdAndEntryDateBetween(userId, from, to);
        Set<Long> usedExpenses = new HashSet<>();
        Set<Long> usedTopUps = new HashSet<>();
        for (StatementImportForm.Row row : rows) {
            if (row.isAtmWithdrawal()) {
                existingTopUps.stream()
                        .filter(t -> !usedTopUps.contains(t.getId()) && t.getAmount().compareTo(row.getAmount()) == 0
                                && withinADay(t.getEntryDate(), row.getExpenseDate()))
                        .findFirst()
                        .ifPresent(t -> {
                            usedTopUps.add(t.getId());
                            markDuplicate(row, "Already in your cash wallet (" + t.getEntryDate().format(SHORT_DATE) + ")");
                        });
                continue;
            }
            Optional<String> ref = upiRef(row.getNote());
            existing.stream()
                    .filter(e -> !usedExpenses.contains(e.getId()))
                    .filter(e -> (ref.isPresent() && e.getNote() != null && e.getNote().contains(ref.get()))
                            || (e.getAmount().compareTo(row.getAmount()) == 0
                                && withinADay(e.getExpenseDate(), row.getExpenseDate())))
                    .findFirst()
                    .ifPresent(e -> {
                        usedExpenses.add(e.getId());
                        markDuplicate(row, "Already added: " + e.getMerchant() + " on " + e.getExpenseDate().format(SHORT_DATE));
                    });
        }
    }

    private static void markDuplicate(StatementImportForm.Row row, String text) {
        row.setDuplicateOf(text);
        row.setInclude(false);
    }

    private static boolean withinADay(LocalDate a, LocalDate b) {
        return Math.abs(ChronoUnit.DAYS.between(a, b)) <= 1;
    }

    private static Optional<String> upiRef(String text) {
        if (text == null || !text.toUpperCase(Locale.ROOT).contains("UPI")) {
            return Optional.empty();
        }
        Matcher m = UPI_REF.matcher(text);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** Saves ticked rows: spending as expenses, ATM withdrawals into the cash wallet. All or nothing. */
    @Transactional
    public SaveResult save(AuthUser user, List<StatementImportForm.Row> rows) {
        List<ExpenseForm> forms = new ArrayList<>();
        int withdrawals = 0;
        for (StatementImportForm.Row row : rows) {
            if (!row.isInclude()) {
                continue;
            }
            if (row.isAtmWithdrawal()) {
                cashService.addTopUp(user.id(), row.getAmount(), row.getExpenseDate(), "ATM withdrawal (bank statement)");
                withdrawals++;
                continue;
            }
            ExpenseForm form = new ExpenseForm();
            form.setMerchant(row.getMerchant());
            form.setAmount(row.getAmount());
            form.setCategory(row.getCategory());
            form.setExpenseDate(row.getExpenseDate());
            form.setPaymentMethod(row.getPaymentMethod());
            form.setNote(row.getNote());
            form.setSource(ExpenseSource.STATEMENT);
            forms.add(form);
        }
        expenseService.createAll(user, forms);
        return new SaveResult(forms.size(), withdrawals);
    }
}
