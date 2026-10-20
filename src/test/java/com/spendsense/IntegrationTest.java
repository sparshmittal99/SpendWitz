package com.spendsense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.spendsense.common.Category;
import com.spendsense.expense.Expense;
import com.spendsense.expense.ExpenseRepository;
import com.spendsense.expense.ExpenseSource;
import com.spendsense.user.AppUser;
import com.spendsense.user.AppUserRepository;
import com.spendsense.user.AuthUser;

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
