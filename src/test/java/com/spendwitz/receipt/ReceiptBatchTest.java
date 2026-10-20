package com.spendwitz.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.spendwitz.IntegrationTest;
import com.spendwitz.common.Category;
import com.spendwitz.expense.BillReference;
import com.spendwitz.expense.ExpenseForm;
import com.spendwitz.expense.ExpenseSource;
import com.spendwitz.user.AuthUser;

@AutoConfigureMockMvc
class ReceiptBatchTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    Clock clock;

    @Autowired
    ReceiptScanService scanner;

    @Test
    void severalBillsThatCantBeReadShowEachReason() throws Exception {
        var user = newUser();
        mvc.perform(multipart("/receipts/scan").with(user(user)).with(csrf())
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()))
                        .file(new MockMultipartFile("file", "song.mp3", "audio/mpeg", new byte[] {1, 2, 3})))
                .andExpect(redirectedUrl("/receipts/scan"))
                .andExpect(flash().attribute("error", containsString("notes.txt")))
                .andExpect(flash().attribute("error", containsString("song.mp3")));
    }

    @Test
    void theSameFileChosenTwiceIsSkippedBeforeScanning() throws Exception {
        var user = newUser();
        byte[] same = "not really a bill".getBytes();
        mvc.perform(multipart("/receipts/scan").with(user(user)).with(csrf())
                        .file(new MockMultipartFile("file", "bill.txt", "text/plain", same))
                        .file(new MockMultipartFile("file", "bill (1).txt", "text/plain", same)))
                .andExpect(redirectedUrl("/receipts/scan"))
                .andExpect(flash().attribute("error", containsString("bill (1).txt: same file as bill.txt")));
    }

    @Test
    void billNumbersAreCleanedUpSoTheSameNumberAlwaysMatches() {
        assertThat(BillReference.normalize(" inv-0042 ")).isEqualTo("INV0042");
        assertThat(BillReference.normalize("INV/0042")).isEqualTo("INV0042");
        assertThat(BillReference.normalize("12")).isNull();          // too short to be unique
        assertThat(BillReference.normalize("N/A")).isNull();         // no digits
        assertThat(BillReference.normalize(null)).isNull();
        assertThat(BillReference.isUpiTransactionId("629914573108")).isTrue();
        assertThat(BillReference.isUpiTransactionId("INV0042")).isFalse();
    }

    @Test
    void spotsABillThatWasAlreadySaved() throws Exception {
        var user = newUser();
        LocalDate day = LocalDate.now(clock).minusDays(2);
        saveScannedBill(user, "DMart", "1200", "INV-0042", day);
        var statementRow = addExpense(user, "Ramesh", "300.00", Category.OTHER, day);
        statementRow.update("Ramesh", new BigDecimal("300.00"), Category.OTHER, day, "UPI/DR/629914573108/RAMESH/okaxis");
        expenses.save(statementRow);

        assertThat(scanner.alreadySaved(user, scanned("inv 0042", "1200.00")))
                .startsWith("Already added: DMart").contains("1200");
        assertThat(scanner.alreadySaved(user, scanned("INV0042", "999.00"))).isNull();     // same number, other shop's bill
        assertThat(scanner.alreadySaved(user, scanned("INV0043", "1200.00"))).isNull();
        assertThat(scanner.alreadySaved(user, scanned("629914573108", "300"))).startsWith("Already added: Ramesh");
        assertThat(scanner.alreadySaved(user, scanned(null, "1200.00"))).isNull();
    }

    @Test
    void theSameBillTwiceInOneUploadIsFlagged() {
        var first = new ReceiptScanService.ScanResult(scanned("INV0042", "1200.00"), "", null);
        var photoOfSameBill = new ReceiptScanService.ScanResult(scanned("INV0042", "1200"), "", null);
        var otherBill = new ReceiptScanService.ScanResult(scanned("INV0043", "1200.00"), "", null);
        var noNumber = new ReceiptScanService.ScanResult(scanned(null, "1200.00"), "", null);

        var marked = ReceiptScanService.markRepeats(List.of(first, photoOfSameBill, otherBill, noNumber));

        assertThat(marked).extracting(ReceiptScanService.ScanResult::duplicateOf)
                .containsExactly(null, "Same bill as another file in this upload", null, null);
    }

    @Test
    void scannedItemsAreWrittenIntoTheNote() {
        var items = List.of(new ExpenseForm.ItemForm("Veg Momos", new BigDecimal("2"), new BigDecimal("180.00")),
                new ExpenseForm.ItemForm("Coke", BigDecimal.ONE, new BigDecimal("40")),
                new ExpenseForm.ItemForm("Napkins", null, null));

        assertThat(ReceiptScanService.withItems(null, items, "₹"))
                .isEqualTo("Items: Veg Momos ×2 ₹180, Coke ₹40, Napkins");
        assertThat(ReceiptScanService.withItems("Includes tax of 28.50", items, "₹"))
                .isEqualTo("Includes tax of 28.50 · Items: Veg Momos ×2 ₹180, Coke ₹40, Napkins");
        assertThat(ReceiptScanService.withItems("UPI ref 629914573108", List.of(), "₹")).isEqualTo("UPI ref 629914573108");

        var many = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> new ExpenseForm.ItemForm("Item number " + i, BigDecimal.ONE, new BigDecimal("10")))
                .toList();
        String note = ReceiptScanService.withItems(null, many, "₹");
        assertThat(note).hasSizeLessThanOrEqualTo(500).startsWith("Items: Item number 0 ₹10").endsWith(", …");
    }

    @Test
    void billsSavedTogetherKeepTheirItems() throws Exception {
        var user = newUser();
        String date = LocalDate.now(clock).toString();
        mvc.perform(post("/quick-add/save").with(user(user)).with(csrf())
                        .param("rows[0].include", "true").param("rows[0].merchant", "Swiggy")
                        .param("rows[0].amount", "600").param("rows[0].category", "FOOD_DINING")
                        .param("rows[0].expenseDate", date).param("rows[0].paymentMethod", "UPI")
                        .param("rows[0].source", "RECEIPT")
                        .param("rows[0].items[0].name", "Veg Momos").param("rows[0].items[0].quantity", "2")
                        .param("rows[0].items[0].price", "180")
                        .param("rows[0].items[1].name", "Coke").param("rows[0].items[1].quantity", "1")
                        .param("rows[0].items[1].price", "40"))
                .andExpect(redirectedUrl("/expenses"));

        var saved = expenses.findByUserIdOrderByExpenseDateDescIdDesc(user.id(), Pageable.unpaged()).getFirst();
        var lines = expenses.findItemLines(user.id(), "%momo%", LocalDate.now(clock), LocalDate.now(clock));
        assertThat(saved.getMerchant()).isEqualTo("Swiggy");
        assertThat(lines).singleElement().satisfies(l -> assertThat(l.price()).isEqualByComparingTo("180"));
    }

    @Test
    void tooManyBillsAtOnceAreRefused() throws Exception {
        var user = newUser();
        var request = multipart("/receipts/scan").with(user(user)).with(csrf());
        for (int i = 0; i < 9; i++) {
            request.file(new MockMultipartFile("file", "bill" + i + ".png", "image/png", new byte[] {1}));
        }
        mvc.perform(request)
                .andExpect(redirectedUrl("/receipts/scan"))
                .andExpect(flash().attribute("error", containsString("up to 8")));
    }

    @Test
    void scannedBillsKeepTheirSourceWhenSavedTogether() throws Exception {
        var user = newUser();
        String date = LocalDate.now(clock).toString();
        mvc.perform(post("/quick-add/save").with(user(user)).with(csrf())
                        .param("rows[0].include", "true").param("rows[0].merchant", "DMart")
                        .param("rows[0].amount", "1200").param("rows[0].category", "GROCERIES")
                        .param("rows[0].expenseDate", date).param("rows[0].paymentMethod", "CARD")
                        .param("rows[0].source", "RECEIPT")
                        .param("rows[1].include", "true").param("rows[1].merchant", "Ramesh")
                        .param("rows[1].amount", "300").param("rows[1].category", "OTHER")
                        .param("rows[1].expenseDate", date).param("rows[1].paymentMethod", "UPI")
                        .param("rows[1].source", "UPI_SCREENSHOT")
                        // a source other than a scan can't be set from the form
                        .param("rows[2].include", "true").param("rows[2].merchant", "Chai")
                        .param("rows[2].amount", "20").param("rows[2].category", "FOOD_DINING")
                        .param("rows[2].expenseDate", date).param("rows[2].paymentMethod", "CASH")
                        .param("rows[2].source", "STATEMENT"))
                .andExpect(redirectedUrl("/expenses"));

        var saved = expenses.findByUserIdOrderByExpenseDateDescIdDesc(user.id(), Pageable.unpaged());
        assertThat(saved).extracting(e -> e.getMerchant() + ":" + e.getSource())
                .containsExactlyInAnyOrder("DMart:" + ExpenseSource.RECEIPT, "Ramesh:" + ExpenseSource.UPI_SCREENSHOT,
                        "Chai:" + ExpenseSource.QUICK_ADD);
    }

    @Test
    void theBillNumberIsStoredOnlyForScannedBills() throws Exception {
        var user = newUser();
        String date = LocalDate.now(clock).toString();
        mvc.perform(post("/quick-add/save").with(user(user)).with(csrf())
                        .param("rows[0].include", "true").param("rows[0].merchant", "DMart")
                        .param("rows[0].amount", "1200").param("rows[0].category", "GROCERIES")
                        .param("rows[0].expenseDate", date).param("rows[0].paymentMethod", "CARD")
                        .param("rows[0].source", "RECEIPT").param("rows[0].reference", "inv-0042")
                        .param("rows[1].include", "true").param("rows[1].merchant", "Chai")
                        .param("rows[1].amount", "20").param("rows[1].category", "FOOD_DINING")
                        .param("rows[1].expenseDate", date).param("rows[1].paymentMethod", "CASH")
                        .param("rows[1].reference", "INV0099"))
                .andExpect(redirectedUrl("/expenses"));

        var saved = expenses.findByUserIdOrderByExpenseDateDescIdDesc(user.id(), Pageable.unpaged());
        assertThat(saved).extracting(e -> e.getMerchant() + ":" + e.getReference())
                .containsExactlyInAnyOrder("DMart:INV0042", "Chai:null");
    }

    private void saveScannedBill(AuthUser user, String merchant, String amount, String reference, LocalDate date)
            throws Exception {
        mvc.perform(post("/expenses").with(user(user)).with(csrf())
                        .param("merchant", merchant).param("amount", amount).param("category", "GROCERIES")
                        .param("expenseDate", date.toString()).param("paymentMethod", "CARD")
                        .param("source", "RECEIPT").param("reference", reference))
                .andExpect(redirectedUrl("/expenses"));
    }

    private static ExpenseForm scanned(String reference, String amount) {
        ExpenseForm form = new ExpenseForm();
        form.setReference(BillReference.normalize(reference));
        form.setAmount(new BigDecimal(amount));
        return form;
    }
}
