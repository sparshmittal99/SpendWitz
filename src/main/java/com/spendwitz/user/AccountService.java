package com.spendwitz.user;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spendwitz.ai.ExpenseIndexer;
import com.spendwitz.expense.ExpenseRepository;

@Service
public class AccountService {

    private final AppUserRepository users;
    private final ExpenseRepository expenses;
    private final ExpenseIndexer indexer;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AccountService(AppUserRepository users, ExpenseRepository expenses, ExpenseIndexer indexer,
            PasswordEncoder passwordEncoder, JdbcTemplate jdbc, Clock clock) {
        this.users = users;
        this.expenses = expenses;
        this.indexer = indexer;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record AccountInfo(AppUser user, LocalDate memberSince, long expenseCount) {
    }

    @Transactional(readOnly = true)
    public AccountInfo info(AuthUser user) {
        AppUser account = users.findById(user.id()).orElseThrow();
        return new AccountInfo(account, LocalDate.ofInstant(account.getCreatedAt(), clock.getZone()),
                expenses.countByUserId(user.id()));
    }

    /**
     * Permanently deletes the user and everything they own. Expenses, items, budgets, insights and cash entries go
     * with the user row (ON DELETE CASCADE); AI usage counters and search embeddings aren't foreign-keyed, so they
     * are removed explicitly. All in one transaction: either everything is gone or nothing is.
     */
    @Transactional
    public void delete(AuthUser user, String password) {
        if (user.demo()) {
            throw new AccountException("The shared demo account can't be deleted. Create your own account to try this.");
        }
        AppUser account = users.findById(user.id()).orElseThrow();
        if (password == null || !passwordEncoder.matches(password, account.getPasswordHash())) {
            throw new AccountException("That password is incorrect, so your account was not deleted.");
        }
        indexer.removeAllForUser(user.id());
        jdbc.update("delete from ai_usage where user_id = ?", user.id());
        users.delete(account);
    }

    public static class AccountException extends RuntimeException {

        public AccountException(String message) {
            super(message);
        }
    }
}
