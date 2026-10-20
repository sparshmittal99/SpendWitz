package com.spendwitz.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.spendwitz.IntegrationTest;
import com.spendwitz.budget.Budget;
import com.spendwitz.budget.BudgetRepository;
import com.spendwitz.cash.CashTopUp;
import com.spendwitz.cash.CashTopUpRepository;
import com.spendwitz.common.Category;

@AutoConfigureMockMvc
class AccountDeletionTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    BudgetRepository budgets;

    @Autowired
    CashTopUpRepository topUps;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    private AuthUser userWithPassword(String password, boolean demo) {
        AppUser user = users.save(new AppUser(UUID.randomUUID() + "@test.dev", passwordEncoder.encode(password),
                "Asha", "INR", demo));
        return new AuthUser(user.getId(), user.getEmail(), user.getPasswordHash(), "Asha", "INR", demo);
    }

    @Test
    void deletingTheAccountRemovesEverythingAndLogsOut() throws Exception {
        var user = userWithPassword("correct-horse", false);
        LocalDate today = LocalDate.now(clock);
        addExpense(user, "Swiggy", "320.00", Category.FOOD_DINING, today);
        budgets.save(new Budget(user.id(), Category.FOOD_DINING, new BigDecimal("5000")));
        topUps.save(new CashTopUp(user.id(), new BigDecimal("2000"), today, "ATM"));
        jdbc.update("insert into ai_usage (user_id, usage_date, request_count) values (?, ?, 3)", user.id(), today);

        mvc.perform(post("/account/delete").with(user(user)).with(csrf()).param("password", "correct-horse"))
                .andExpect(redirectedUrl("/login?deleted"));

        assertThat(users.findById(user.id())).isEmpty();
        assertThat(expenses.countByUserId(user.id())).isZero();
        assertThat(budgets.findByUserIdOrderByCategory(user.id())).isEmpty();
        assertThat(topUps.totalForUser(user.id())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from ai_usage where user_id = ?", Long.class, user.id())).isZero();
    }

    @Test
    void aWrongPasswordKeepsTheAccount() throws Exception {
        var user = userWithPassword("correct-horse", false);

        mvc.perform(post("/account/delete").with(user(user)).with(csrf()).param("password", "nope"))
                .andExpect(redirectedUrl("/account"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("incorrect")));

        assertThat(users.findById(user.id())).isPresent();
    }

    @Test
    void theSharedDemoAccountCannotBeDeleted() throws Exception {
        var demo = userWithPassword("demo1234", true);

        mvc.perform(post("/account/delete").with(user(demo)).with(csrf()).param("password", "demo1234"))
                .andExpect(redirectedUrl("/account"));

        assertThat(users.findById(demo.id())).isPresent();
    }

    @Test
    void accountPageRenders() throws Exception {
        var user = userWithPassword("correct-horse", false);
        addExpense(user, "Chai", "20", Category.FOOD_DINING, LocalDate.now(clock));
        mvc.perform(get("/account").with(user(user))).andExpect(status().isOk());
        mvc.perform(get("/account").with(user(userWithPassword("demo1234", true)))).andExpect(status().isOk());
    }
}
