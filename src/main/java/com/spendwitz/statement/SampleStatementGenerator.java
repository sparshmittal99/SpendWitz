package com.spendwitz.statement;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.spendwitz.common.PaymentMethod;
import com.spendwitz.expense.Expense;
import com.spendwitz.expense.ExpenseRepository;
import com.spendwitz.user.AuthUser;

/**
 * Builds a realistic sample statement from a fictional bank, so visitors can try statement import without
 * uploading real bank data. It mixes some of the user's own recent UPI/card expenses (to show duplicate
 * detection) with new purchases, salary, an ATM withdrawal, a card bill payment and a transfer to self.
 */
@Component
public class SampleStatementGenerator {

    /** The sample PDF is password-protected like real Indian bank statements. */
    public static final String SAMPLE_PDF_PASSWORD = "demo1234";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yy", Locale.ENGLISH);
    private static final String[] HEADERS =
            {"Date", "Narration", "Chq./Ref.No.", "Value Dt", "Withdrawal Amt.", "Deposit Amt.", "Closing Balance"};

    record Txn(LocalDate date, String narration, String ref, BigDecimal withdrawal, BigDecimal deposit) {
    }

    private final ExpenseRepository expenses;
    private final Clock clock;

    public SampleStatementGenerator(ExpenseRepository expenses, Clock clock) {
        this.expenses = expenses;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public byte[] excel(AuthUser user) {
        List<Txn> txns = transactions(user);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Statement");
            CellStyle money = workbook.createCellStyle();
            money.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            int r = 0;
            for (String line : headerLines(user, txns)) {
                sheet.createRow(r++).createCell(0).setCellValue(line);
            }
            r++;
            Row header = sheet.createRow(r++);
            for (int c = 0; c < HEADERS.length; c++) {
                header.createCell(c).setCellValue(HEADERS[c]);
            }
            BigDecimal balance = openingBalance();
            for (Txn t : txns) {
                balance = balance.subtract(orZero(t.withdrawal())).add(orZero(t.deposit()));
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(t.date().format(DATE));
                row.createCell(1).setCellValue(t.narration());
                row.createCell(2).setCellValue(t.ref());
                row.createCell(3).setCellValue(t.date().format(DATE));
                if (t.withdrawal() != null) {
                    var cell = row.createCell(4);
                    cell.setCellValue(t.withdrawal().doubleValue());
                    cell.setCellStyle(money);
                }
                if (t.deposit() != null) {
                    var cell = row.createCell(5);
                    cell.setCellValue(t.deposit().doubleValue());
                    cell.setCellStyle(money);
                }
                var bal = row.createCell(6);
                bal.setCellValue(balance.doubleValue());
                bal.setCellStyle(money);
            }
            sheet.setColumnWidth(0, 10 * 256);
            sheet.setColumnWidth(1, 62 * 256);
            sheet.setColumnWidth(2, 16 * 256);
            for (int c = 3; c < HEADERS.length; c++) {
                sheet.setColumnWidth(c, 16 * 256);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Transactional(readOnly = true)
    public byte[] pdf(AuthUser user) {
        List<Txn> txns = transactions(user);
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            float[] x = {36, 84, 300, 368, 418, 482, 548};
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float y = 800;
                text(content, bold, 14, 36, y, "SpendWitz Demo Bank");
                y -= 16;
                for (String line : headerLines(user, txns).subList(1, 5)) {
                    text(content, regular, 9, 36, y, line);
                    y -= 12;
                }
                y -= 14;
                String[] headers = {"Date", "Narration", "Ref No.", "Value Dt", "Withdrawal", "Deposit", "Balance"};
                for (int c = 0; c < headers.length; c++) {
                    text(content, bold, 8, x[c], y, headers[c]);
                }
                y -= 14;
                BigDecimal balance = openingBalance();
                text(content, regular, 7.5f, x[1], y, "OPENING BALANCE");
                text(content, regular, 7.5f, x[6], y, money(balance));
                y -= 12;
                for (Txn t : txns) {
                    balance = balance.subtract(orZero(t.withdrawal())).add(orZero(t.deposit()));
                    text(content, regular, 7.5f, x[0], y, t.date().format(DATE));
                    text(content, regular, 7.5f, x[1], y, ascii(t.narration(), 50));
                    text(content, regular, 7.5f, x[2], y, t.ref());
                    text(content, regular, 7.5f, x[3], y, t.date().format(DATE));
                    if (t.withdrawal() != null) {
                        text(content, regular, 7.5f, x[4], y, money(t.withdrawal()));
                    }
                    if (t.deposit() != null) {
                        text(content, regular, 7.5f, x[5], y, money(t.deposit()));
                    }
                    text(content, regular, 7.5f, x[6], y, money(balance));
                    y -= 12;
                }
                y -= 10;
                text(content, regular, 7.5f, x[1], y, "CLOSING BALANCE");
                text(content, regular, 7.5f, x[6], y, money(balance));
                text(content, regular, 7, 36, 40, "This is a sample statement from a fictional bank, generated by SpendWitz for demo purposes.");
            }
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    "spendwitz-owner", SAMPLE_PDF_PASSWORD, new AccessPermission());
            policy.setEncryptionKeyLength(256);
            document.protect(policy);
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<Txn> transactions(AuthUser user) {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = today.minusDays(14);
        List<Txn> txns = new ArrayList<>();

        // A few of the user's own digital payments, written the way a bank prints them (shows duplicate detection)
        List<Expense> recent = expenses.findByUserIdOrderByExpenseDateDescIdDesc(user.id(), PageRequest.of(0, 60));
        recent.stream()
                .filter(e -> !e.getExpenseDate().isBefore(from) && e.getPaymentMethod() != PaymentMethod.CASH)
                .limit(6)
                .forEach(e -> txns.add(new Txn(e.getExpenseDate(), bankNarration(e), ref(e.getId()), e.getAmount(), null)));

        String self = user.displayName().toUpperCase(Locale.ROOT);
        txns.add(new Txn(today.minusDays(13), "SALARY CREDIT ACME TECHNOLOGIES PVT LTD OCT", "SAL0000412", null, money("85000")));
        txns.add(new Txn(today.minusDays(11), "UPI/DR/630100112233/LENSKART SOLUTIONS/lenskart.payu@hdfcbank/Eyeglasses", "630100112233", money("1800"), null));
        txns.add(new Txn(today.minusDays(10), "ATM WDL/MG ROAD BANGALORE/S1AWBL023", "ATM0451", money("2000"), null));
        txns.add(new Txn(today.minusDays(9), "ACH D- HDFC MUTUAL FUND SIP-INV 0123456", "ACH91200", money("5000"), null));
        txns.add(new Txn(today.minusDays(8), "POS 4821XXXXXX4821 DECATHLON SPORTS INDIA BANGALORE", "POS77120", money("2499"), null));
        txns.add(new Txn(today.minusDays(7), "UPI/CR/630155667788/RAHUL SHARMA/rahul@okicici/Dinner split", "630155667788", null, money("450")));
        txns.add(new Txn(today.minusDays(6), "IMPS/P2A/630177889900/" + self + "/HDFC0001234/Savings transfer", "630177889900", money("10000"), null));
        txns.add(new Txn(today.minusDays(5), "BIL/ICICI CREDIT CARD/4375XXXXXXXX1002/Card payment", "BIL55102", money("12450"), null));
        txns.add(new Txn(today.minusDays(4), "UPI/DR/630199001122/IRCTC/irctc@hdfcbank/Train tickets", "630199001122", money("1245"), null));
        txns.add(new Txn(today.minusDays(3), "UPI/DR/630122334455/ZEPTO MARKETPLACE/zepto@ybl/Groceries", "630122334455", money("642"), null));
        txns.add(new Txn(today.minusDays(2), "NACH DR/BAJAJ FINANCE LTD/EMI 4 OF 12", "NACH6620", money("1999"), null));
        txns.add(new Txn(today.minusDays(1), "INT.PD:01-07-2026 TO 30-09-2026", "INT0001", null, money("312")));
        txns.sort(Comparator.comparing(Txn::date));
        return txns;
    }

    private List<String> headerLines(AuthUser user, List<Txn> txns) {
        return List.of(
                "SpendWitz Demo Bank",
                "Statement of account",
                "Account holder: " + user.displayName().toUpperCase(Locale.ROOT),
                "Account no: XXXXXXXX4821   Branch: MG Road, Bengaluru",
                "Period: " + txns.getFirst().date().format(DATE) + " to " + txns.getLast().date().format(DATE));
    }

    private static String bankNarration(Expense e) {
        String name = e.getMerchant().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", "");
        return switch (e.getPaymentMethod()) {
            case UPI -> "UPI/DR/" + ref(e.getId()) + "/" + name + "/" + name.replace(" ", "").toLowerCase(Locale.ROOT)
                    + "@okaxis/Payment";
            case CARD -> "POS 4821XXXXXX4821 " + name + " BANGALORE";
            default -> "NEFT DR-N" + ref(e.getId()) + "-" + name;
        };
    }

    private static String ref(long id) {
        return "6299" + String.format("%08d", id % 100_000_000);
    }

    private static BigDecimal openingBalance() {
        return money("142350.75");
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String money(BigDecimal value) {
        return String.format(Locale.ENGLISH, "%,.2f", value);
    }

    private static String ascii(String s, int max) {
        String clean = s.replaceAll("[^\\x20-\\x7E]", "");
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static void text(PDPageContentStream content, PDType1Font font, float size, float x, float y, String text)
            throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(ascii(text, 200));
        content.endText();
    }
}
