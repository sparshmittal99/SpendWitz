package com.spendwitz.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;

import com.spendwitz.IntegrationTest;
import com.spendwitz.cash.CashTopUpRepository;
import com.spendwitz.common.Category;
import com.spendwitz.common.PaymentMethod;
import com.spendwitz.expense.ExpenseSource;

@AutoConfigureMockMvc
class StatementImportTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    StatementReader reader;

    @Autowired
    SampleStatementGenerator samples;

    @Autowired
    StatementImportService importer;

    @Autowired
    CashTopUpRepository topUps;

    @Autowired
    Clock clock;

    @Test
    void sampleExcelCanBeReadBackAndParsed() {
        var user = newUser();
        var table = (StatementReader.Table) reader.read("s.xlsx", samples.excel(user), null);

        int header = 0;
        while (!table.rows().get(header).contains("Narration")) {
            header++;
        }
        var mapping = new TableExtractor.ColumnMapping(header, 0, 1, 4, 5, null, null, "DEBIT_COLUMN", null, "dd/MM/yy");
        var result = TableExtractor.extract(table.rows(), mapping);

        // 9 withdrawals and 3 deposits in the fixed part of the sample (the user has no expenses of their own)
        assertThat(result.debits()).hasSize(9);
        assertThat(result.credits()).isEqualTo(3);
        assertThat(result.debits()).anySatisfy(line -> {
            assertThat(line.description()).contains("LENSKART");
            assertThat(line.amount()).isEqualByComparingTo("1800");
        });
    }

    @Test
    void samplePdfIsPasswordProtectedLikeARealStatement() {
        var user = newUser();
        byte[] pdf = samples.pdf(user);

        assertThatThrownBy(() -> reader.read("s.pdf", pdf, null))
                .isInstanceOf(StatementException.class).hasMessageContaining("password");
        assertThatThrownBy(() -> reader.read("s.pdf", pdf, "wrong"))
                .isInstanceOf(StatementException.class).hasMessageContaining("didn't open");

        var content = (StatementReader.Pdf) reader.read("s.pdf", pdf, SampleStatementGenerator.SAMPLE_PDF_PASSWORD);
        String text = String.join("\n", content.pages());
        assertThat(content.hasText()).isTrue();
        assertThat(text).contains("LENSKART").contains("1,800.00");
        assertThat(Amounts.appearsIn(text, new BigDecimal("1800"))).isTrue();
    }

    @Test
    void flagsTransactionsThatAreAlreadyInSpendWitz() {
        var user = newUser();
        LocalDate today = LocalDate.now(clock);
        addExpense(user, "Zepto", "642.00", Category.GROCERIES, today.minusDays(1));
        var screenshot = addExpense(user, "Gupta General Store", "350.00", Category.GROCERIES, today.minusDays(5));
        screenshot.update("Gupta General Store", new BigDecimal("350.00"), Category.GROCERIES, today.minusDays(5),
                "UPI ref 629914573108");
        expenses.save(screenshot);

        List<StatementImportForm.Row> rows = new ArrayList<>(List.of(
                row("Zepto Marketplace", "642.00", today, "UPI/DR/630122334455/ZEPTO MARKETPLACE/zepto@ybl"),
                row("Gupta General", "350.00", today.minusDays(4), "UPI/DR/629914573108/GUPTA GENERAL/okaxis"),
                row("Zepto Marketplace", "642.00", today, "UPI/DR/630122339999/ZEPTO MARKETPLACE/zepto@ybl"),
                row("IRCTC", "1245.00", today, "UPI/DR/630199001122/IRCTC/irctc@hdfcbank")));

        importer.markDuplicates(user.id(), rows);

        assertThat(rows.get(0).getDuplicateOf()).contains("Zepto");       // same amount, a day apart
        assertThat(rows.get(1).getDuplicateOf()).contains("Gupta");       // same UPI reference
        assertThat(rows.get(2).getDuplicateOf()).isNull();               // the one Zepto expense was already matched
        assertThat(rows.get(3).getDuplicateOf()).isNull();
        assertThat(rows.get(0).isInclude()).isFalse();
        assertThat(rows.get(3).isInclude()).isTrue();
    }

    @Test
    void savesExpensesAndSendsAtmWithdrawalsToTheCashWallet() throws Exception {
        var user = newUser();
        String date = LocalDate.now(clock).minusDays(2).toString();
        mvc.perform(post("/import/save").with(user(user)).with(csrf())
                        .param("rows[0].include", "true").param("rows[0].kind", "EXPENSE")
                        .param("rows[0].merchant", "IRCTC").param("rows[0].amount", "1245.00")
                        .param("rows[0].category", "TRAVEL").param("rows[0].expenseDate", date)
                        .param("rows[0].paymentMethod", "UPI").param("rows[0].note", "UPI/DR/630199001122/IRCTC")
                        .param("rows[1].include", "true").param("rows[1].kind", "ATM_WITHDRAWAL")
                        .param("rows[1].merchant", "ATM withdrawal").param("rows[1].amount", "2000")
                        .param("rows[1].expenseDate", date)
                        .param("rows[2].include", "false").param("_rows[2].include", "on").param("rows[2].kind", "EXPENSE")
                        .param("rows[2].merchant", "Zepto").param("rows[2].amount", "642")
                        .param("rows[2].category", "GROCERIES").param("rows[2].expenseDate", date)
                        .param("rows[2].paymentMethod", "UPI"))
                .andExpect(redirectedUrl("/expenses"));

        var saved = expenses.findByUserIdOrderByExpenseDateDescIdDesc(user.id(), Pageable.unpaged());
        assertThat(saved).singleElement().satisfies(e -> {
            assertThat(e.getMerchant()).isEqualTo("IRCTC");
            assertThat(e.getSource()).isEqualTo(ExpenseSource.STATEMENT);
            assertThat(e.getPaymentMethod()).isEqualTo(PaymentMethod.UPI);
        });
        assertThat(topUps.totalForUser(user.id())).isEqualByComparingTo("2000");
    }

    @Test
    void importPageRendersAndSamplesDownload() throws Exception {
        var user = newUser();
        mvc.perform(get("/import").with(user(user))).andExpect(status().isOk());
        mvc.perform(get("/import/sample-statement.xlsx").with(user(user))).andExpect(status().isOk());
        mvc.perform(get("/import/sample-statement.pdf").with(user(user))).andExpect(status().isOk());
        mvc.perform(get("/import/sample-statement.exe").with(user(user))).andExpect(status().isNotFound());
    }

    private static StatementImportForm.Row row(String merchant, String amount, LocalDate date, String note) {
        var row = new StatementImportForm.Row();
        row.setMerchant(merchant);
        row.setAmount(new BigDecimal(amount));
        row.setExpenseDate(date);
        row.setNote(note);
        row.setCategory(Category.OTHER);
        row.setPaymentMethod(PaymentMethod.UPI);
        return row;
    }
}
