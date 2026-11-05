package com.spendwitz.push;

import java.time.YearMonth;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.spendwitz.common.Category;

/** Phones to notify (Firebase tokens) and which over-budget alerts were already sent. */
@Repository
public class PushTokenStore {

    private final JdbcClient jdbc;

    public PushTokenStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void register(long userId, String token) {
        jdbc.sql("""
                insert into push_token (token, user_id) values (:token, :userId)
                on conflict (token) do update set user_id = excluded.user_id, updated_at = now()
                """)
                .param("token", token)
                .param("userId", userId)
                .update();
    }

    public void remove(String token) {
        jdbc.sql("delete from push_token where token = :token").param("token", token).update();
    }

    public List<String> tokensFor(long userId) {
        return jdbc.sql("select token from push_token where user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .list();
    }

    /** Records the alert; false if it was already sent this month. Atomic, so parallel saves alert only once. */
    public boolean markAlertSent(long userId, Category category, YearMonth month) {
        return jdbc.sql("""
                insert into budget_alert_sent (user_id, category, month) values (:userId, :category, :month)
                on conflict do nothing
                """)
                .param("userId", userId)
                .param("category", category.name())
                .param("month", month.toString())
                .update() == 1;
    }

    public void clearAlertSent(long userId, Category category, YearMonth month) {
        jdbc.sql("delete from budget_alert_sent where user_id = :userId and category = :category and month = :month")
                .param("userId", userId)
                .param("category", category.name())
                .param("month", month.toString())
                .update();
    }
}
