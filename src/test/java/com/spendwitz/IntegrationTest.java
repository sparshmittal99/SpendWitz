package com.spendwitz;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.spendwitz.common.Category;
import com.spendwitz.expense.Expense;
import com.spendwitz.expense.ExpenseRepository;
import com.spendwitz.expense.ExpenseSource;
import com.spendwitz.user.AppUser;
import com.spendwitz.user.AppUserRepository;
import com.spendwitz.user.AuthUser;

/** Real Postgres + pgvector in Docker; the vector store is mocked so tests never call Gemini. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    @Autowired
    protected AppUserRepository users;

    @Autowired
    protected ExpenseRepository expenses;

    @MockitoBean
    protected VectorStore vectorStore;

    protected AuthUser newUser() {
        AppUser user = users.save(new AppUser(UUID.randomUUID() + "@test.dev", "{noop}password", "Test", "INR", false));
        return new AuthUser(user.getId(), user.getEmail(), user.getPasswordHash(), "Test", "INR", false);
    }

    protected Expense addExpense(AuthUser user, String merchant, String amount, Category category, LocalDate date) {
        Expense expense = new Expense(users.getReferenceById(user.id()), ExpenseSource.MANUAL);
        expense.update(merchant, new BigDecimal(amount), category, date, null);
        return expenses.save(expense);
    }
}
