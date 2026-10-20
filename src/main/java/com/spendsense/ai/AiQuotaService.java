package com.spendsense.ai;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.spendsense.common.QuotaExceededException;
import com.spendsense.config.AppProperties;
import com.spendsense.user.AuthUser;

/**
 * Caps AI calls per user per day, plus an app-wide daily cap, so a public demo can't burn through
 * the free Gemini quota. Counters live in Postgres so they survive restarts and work across instances.
 */
@Service
public class AiQuotaService {

    static final long GLOBAL_COUNTER = 0L;

    private static final String INCREMENT = """
            insert into ai_usage (user_id, usage_date, request_count) values (?, ?, 1)
            on conflict (user_id, usage_date) do update set request_count = ai_usage.request_count + 1
            returning request_count""";

    private final JdbcTemplate jdbc;
    private final AppProperties.Ai limits;
    private final Clock clock;

    public AiQuotaService(JdbcTemplate jdbc, AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.limits = props.ai();
        this.clock = clock;
    }

    /**
     * Records one AI call, or throws if today's allowance is used up. Runs in its own transaction so it works
     * from read-only callers and the count sticks even if the caller later rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consume(AuthUser user) {
        LocalDate today = LocalDate.now(clock);
        if (increment(GLOBAL_COUNTER, today) > limits.dailyLimitGlobal()) {
            throw new QuotaExceededException(
                    "SpendSense has hit its AI limit for today (it runs on a free tier). Please try again tomorrow.");
        }
        if (increment(user.id(), today) > limitFor(user)) {
            throw new QuotaExceededException("You've used all " + limitFor(user)
                    + " AI requests for today. Your quota resets at midnight.");
        }
    }

    public int remainingToday(AuthUser user) {
        Integer used = jdbc.query("select request_count from ai_usage where user_id = ? and usage_date = ?",
                rs -> rs.next() ? rs.getInt(1) : 0, user.id(), LocalDate.now(clock));
        return Math.max(0, limitFor(user) - (used == null ? 0 : used));
    }

    private int limitFor(AuthUser user) {
        return user.demo() ? limits.dailyLimitDemo() : limits.dailyLimitPerUser();
    }

    private int increment(long userId, LocalDate day) {
        Integer count = jdbc.queryForObject(INCREMENT, Integer.class, userId, day);
        return count == null ? 0 : count;
    }
}
