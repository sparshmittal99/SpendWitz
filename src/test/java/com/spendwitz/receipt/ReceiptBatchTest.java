package com.spendwitz.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.time.Clock;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.spendwitz.IntegrationTest;
import com.spendwitz.expense.ExpenseSource;

@AutoConfigureMockMvc
class ReceiptBatchTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    Clock clock;

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
}
