package com.spendsense.dashboard;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spendsense.ai.AiQuotaService;
import com.spendsense.ai.ResilientChat;
import com.spendsense.budget.BudgetService;
import com.spendsense.common.AiUnavailableException;
import com.spendsense.common.Money;
import com.spendsense.expense.CategoryTotal;
import com.spendsense.expense.ExpenseRepository;
import com.spendsense.user.AuthUser;

/**
 * Turns this month's numbers into a few plain-English insights. The numbers are computed in SQL first and
 * handed to the model as facts, so the model only writes; it never does the arithmetic.
 */
@Service
public class InsightService {

    private static final Logger log = LoggerFactory.getLogger(InsightService.class);

    private static final String PROMPT = """
            You are a friendly personal-finance coach. Based only on the facts below, write 3 or 4 short insights
            for the user about their spending this month. Each insight is one sentence of at most 25 words,
            mentions concrete amounts or percentages from the facts, and where useful suggests one specific action.
            Be encouraging, not preachy. Do not invent numbers that are not in the facts.

            Facts:
            {facts}""";

    private record Insights(List<String> insights) {
    }

    private final ResilientChat chat;
    private final AiQuotaService quota;
    private final ExpenseRepository expenses;
    private final BudgetService budgets;
    private final MonthlyInsightRepository repository;
    private final Clock clock;

    public InsightService(ResilientChat chat, AiQuotaService quota, ExpenseRepository expenses,
            BudgetService budgets, MonthlyInsightRepository repository, Clock clock) {
        this.chat = chat;
        this.quota = quota;
        this.expenses = expenses;
        this.budgets = budgets;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void generate(AuthUser user) {
        LocalDate today = LocalDate.now(clock);
        YearMonth month = YearMonth.from(today);
        if (expenses.sumBetween(user.id(), month.atDay(1), today).signum() == 0) {
            throw new AiUnavailableException("Add a few expenses this month first, then generate insights.");
        }
        String facts = facts(user, today);
        quota.consume(user);

        Insights result;
        try {
            result = chat.call(prompt -> prompt
                    .user(u -> u.text(PROMPT).param("facts", facts))
                    .call()
                    .entity(Insights.class));
        } catch (Exception e) {
            log.warn("Insight generation failed", e);
            throw new AiUnavailableException("The AI service is busy right now. Try again in a minute.", e);
        }
        if (result == null || result.insights() == null || result.insights().isEmpty()) {
            throw new AiUnavailableException("The AI didn't return any insights. Try again.");
        }
        MonthlyInsight insight = repository.findByUserIdAndMonth(user.id(), month.toString())
                .orElseGet(() -> new MonthlyInsight(user.id(), month.toString()));
        insight.replace(result.insights().stream().map(s -> s.replace('\n', ' ').trim()).limit(5).toList());
        repository.save(insight);
    }

    private String facts(AuthUser user, LocalDate today) {
        String s = Money.symbol(user.currency());
        Function<BigDecimal, String> m = amount -> s + String.format(Locale.ENGLISH, "%,.0f", amount);
        YearMonth month = YearMonth.from(today);
        YearMonth last = month.minusMonths(1);
        LocalDate lastSameDay = last.atDay(Math.min(today.getDayOfMonth(), last.lengthOfMonth()));

        List<CategoryTotal> thisMonth = expenses.totalsByCategory(user.id(), month.atDay(1), today);
        List<CategoryTotal> lastMonthSamePeriod = expenses.totalsByCategory(user.id(), last.atDay(1), lastSameDay);

        StringBuilder facts = new StringBuilder();
        facts.append("- Today is ").append(today).append(", day ").append(today.getDayOfMonth())
                .append(" of ").append(month.lengthOfMonth()).append(".\n");
        facts.append("- Spent this month so far: ").append(m.apply(expenses.sumBetween(user.id(), month.atDay(1), today))).append('\n');
        facts.append("- Spent in the same period last month: ").append(m.apply(expenses.sumBetween(user.id(), last.atDay(1), lastSameDay))).append('\n');
        facts.append("- Total for all of last month: ").append(m.apply(expenses.sumBetween(user.id(), last.atDay(1), last.atEndOfMonth()))).append('\n');
        facts.append("- This month by category: ").append(format(thisMonth, m)).append('\n');
        facts.append("- Same period last month by category: ").append(format(lastMonthSamePeriod, m)).append('\n');
        facts.append("- Top merchants this month: ").append(expenses
                .topMerchants(user.id(), month.atDay(1), today, PageRequest.of(0, 5)).stream()
                .map(t -> t.merchant() + " " + m.apply(t.total()) + " (" + t.count() + " times)")
                .collect(Collectors.joining(", "))).append('\n');
        facts.append("- This month by payment method: ").append(expenses
                .totalsByPaymentMethod(user.id(), month.atDay(1), today).stream()
                .map(p -> p.paymentMethod().getLabel() + " " + m.apply(p.total()) + " (" + p.count() + " payments)")
                .collect(Collectors.joining(", "))).append('\n');
        var budgetLines = budgets.statusFor(user.id(), month).stream()
                .map(b -> b.category().getLabel() + ": " + m.apply(b.spent()) + " of " + m.apply(b.limit()) + " (" + b.percentUsed() + "%)")
                .collect(Collectors.joining(", "));
        facts.append("- Budgets: ").append(budgetLines.isEmpty() ? "none set" : budgetLines).append('\n');
        return facts.toString();
    }

    private static String format(List<CategoryTotal> totals, Function<BigDecimal, String> money) {
        if (totals.isEmpty()) {
            return "nothing";
        }
        return totals.stream()
                .map(t -> t.category().getLabel() + " " + money.apply(t.total()))
                .collect(Collectors.joining(", "));
    }
}
