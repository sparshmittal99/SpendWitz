package com.spendsense.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;

import com.spendsense.IntegrationTest;
import com.spendsense.cash.CashService;
import com.spendsense.common.Category;
import com.spendsense.common.PaymentMethod;
import com.spendsense.expense.ExpenseSource;

/** Renders every page for a user with data, so template errors fail the build instead of the demo. */
@AutoConfigureMockMvc
class PagesTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    Clock clock;

    @ParameterizedTest
    @ValueSource(strings = {"/", "/login", "/register"})
    void publicPagesRender(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk());
    }

    @Test
    void appPagesRequireLogin() throws Exception {
        mvc.perform(get("/dashboard")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/dashboard", "/expenses", "/expenses/new", "/budgets", "/assistant", "/receipts/scan", "/search",
            "/expenses?category=FOOD_DINING&q=swig", "/expenses?method=CASH", "/cash", "/quick-add", "/quick-add?method=CASH"})
    void appPagesRenderForUserWithData(String path) throws Exception {
        var user = newUser();
        LocalDate today = LocalDate.now(clock);
        addExpense(user, "Swiggy", "320.00", Category.FOOD_DINING, today);
        addExpense(user, "Uber", "180.00", Category.TRANSPORT, today.minusMonths(1));

        mvc.perform(get(path).with(user(user))).andExpect(status().isOk());
    }

    @Test
    void smartSearchRunsWithoutError() throws Exception {
        mvc.perform(get("/search").param("q", "coffee near office").with(user(newUser())))
                .andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("error"))
                .andExpect(model().attributeExists("results"));
    }

    @Test
    void dashboardRendersForNewUser() throws Exception {
        mvc.perform(get("/dashboard").with(user(newUser()))).andExpect(status().isOk());
    }

    @Test
    void createEditAndDeleteExpense() throws Exception {
        var user = newUser();
        mvc.perform(post("/expenses").with(user(user)).with(csrf())
                        .param("merchant", "Third Wave Coffee")
                        .param("amount", "320.50")
                        .param("category", "FOOD_DINING")
                        .param("expenseDate", LocalDate.now(clock).toString())
                        .param("note", "Coffee near office")
                        .param("source", "MANUAL")
                        .param("items[0].name", "Latte")
                        .param("items[0].quantity", "1")
                        .param("items[0].price", "320.50"))
                .andExpect(redirectedUrl("/expenses"));

        long id = expenses.findByUserIdOrderByExpenseDateDescIdDesc(user.id(), Pageable.unpaged())
                .getFirst().getId();
        mvc.perform(get("/expenses/" + id + "/edit").with(user(user))).andExpect(status().isOk());

        // another user cannot see or delete it
        mvc.perform(get("/expenses/" + id + "/edit").with(user(newUser()))).andExpect(status().isNotFound());

        mvc.perform(post("/expenses/" + id + "/delete").with(user(user)).with(csrf()))
                .andExpect(redirectedUrl("/expenses"));
    }

    @Test
    void cashExpenseIsSavedAsCashAndUpdatesTheWallet() throws Exception {
        var user = newUser();
        String today = LocalDate.now(clock).toString();
        mvc.perform(post("/cash/topup").with(user(user)).with(csrf())
                        .param("amount", "500").param("date", today).param("note", "ATM withdrawal"))
                .andExpect(redirectedUrl("/cash"));
        mvc.perform(post("/cash/expense").with(user(user)).with(csrf())
                        .param("what", "Sabzi").param("amount", "120").param("category", "GROCERIES").param("date", today))
                .andExpect(redirectedUrl("/cash"));

        var saved = expenses.findByUserIdOrderByExpenseDateDescIdDesc(user.id(), Pageable.unpaged()).getFirst();
        assertThat(saved.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);

        var page = mvc.perform(get("/cash").with(user(user))).andExpect(status().isOk()).andReturn();
        var summary = (CashService.CashSummary) page.getModelAndView().getModel().get("summary");
        assertThat(summary.cashInHand()).isEqualByComparingTo("380");
    }

    @Test
    void invalidCashExpenseShowsTheFormAgain() throws Exception {
        mvc.perform(post("/cash/expense").with(user(newUser())).with(csrf()).param("what", "").param("amount", "0"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "what", "amount", "category"));
    }

    @Test
    void quickAddSavesOnlyTickedRows() throws Exception {
        var user = newUser();
        String today = LocalDate.now(clock).toString();
        mvc.perform(post("/quick-add/save").with(user(user)).with(csrf())
                        .param("voice", "true")
                        .param("rows[0].include", "true").param("rows[0].merchant", "Chai").param("rows[0].amount", "20")
                        .param("rows[0].category", "FOOD_DINING").param("rows[0].expenseDate", today)
                        .param("rows[0].paymentMethod", "CASH")
                        .param("rows[1].include", "false").param("_rows[1].include", "on")
                        .param("rows[1].merchant", "Sabzi").param("rows[1].amount", "150")
                        .param("rows[1].category", "GROCERIES").param("rows[1].expenseDate", today)
                        .param("rows[1].paymentMethod", "CASH"))
                .andExpect(redirectedUrl("/expenses"));

        var saved = expenses.findByUserIdOrderByExpenseDateDescIdDesc(user.id(), Pageable.unpaged());
        assertThat(saved).singleElement()
                .satisfies(e -> {
                    assertThat(e.getMerchant()).isEqualTo("Chai");
                    assertThat(e.getSource()).isEqualTo(ExpenseSource.VOICE);
                });
    }

    @Test
    void quickAddWithInvalidRowShowsErrors() throws Exception {
        mvc.perform(post("/quick-add/save").with(user(newUser())).with(csrf())
                        .param("rows[0].include", "true").param("rows[0].merchant", "Chai").param("rows[0].amount", "-1")
                        .param("rows[0].category", "FOOD_DINING").param("rows[0].paymentMethod", "CASH"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("errors"));
    }

    @Test
    void invalidExpenseShowsTheFormAgain() throws Exception {
        mvc.perform(post("/expenses").with(user(newUser())).with(csrf())
                        .param("merchant", "")
                        .param("amount", "-5"))
                .andExpect(status().isOk());
    }
}
