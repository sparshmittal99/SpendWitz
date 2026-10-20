package com.spendsense.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spendsense.budget.BudgetService;
import com.spendsense.expense.ExpenseRepository;
import com.spendsense.expense.MonthTotal;

@Service
public class DashboardService {

    private static final int TREND_MONTHS = 6;
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);

    private final ExpenseRepository expenses;
    private final BudgetService budgets;
    private final MonthlyInsightRepository insights;
    private final Clock clock;

    public DashboardService(ExpenseRepository expenses, BudgetService budgets, MonthlyInsightRepository insights,
            Clock clock) {
        this.expenses = expenses;
        this.budgets = budgets;
        this.insights = insights;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardView build(long userId) {
        LocalDate today = LocalDate.now(clock);
        YearMonth month = YearMonth.from(today);
        LocalDate monthStart = month.atDay(1);

        BigDecimal monthToDate = expenses.sumBetween(userId, monthStart, today);
        // Compare with the same number of days last month, so the 5th of the month isn't compared to a full month.
        YearMonth lastMonth = month.minusMonths(1);
        LocalDate lastMonthSameDay = lastMonth.atDay(Math.min(today.getDayOfMonth(), lastMonth.lengthOfMonth()));
        BigDecimal lastMonthSamePeriod = expenses.sumBetween(userId, lastMonth.atDay(1), lastMonthSameDay);
        Integer change = lastMonthSamePeriod.signum() == 0 ? null
                : monthToDate.subtract(lastMonthSamePeriod).multiply(BigDecimal.valueOf(100))
                        .divide(lastMonthSamePeriod, 0, RoundingMode.HALF_UP).intValue();

        YearMonth firstTrendMonth = month.minusMonths(TREND_MONTHS - 1L);
        Map<String, BigDecimal> monthly = expenses.monthlyTotals(userId, firstTrendMonth.atDay(1)).stream()
                .collect(Collectors.toMap(MonthTotal::getMonth, MonthTotal::getTotal));
        List<String> trendLabels = new ArrayList<>();
        List<Double> trendValues = new ArrayList<>();
        for (YearMonth m = firstTrendMonth; !m.isAfter(month); m = m.plusMonths(1)) {
            trendLabels.add(m.format(MONTH_LABEL));
            trendValues.add(monthly.getOrDefault(m.toString(), BigDecimal.ZERO).doubleValue());
        }

        return new DashboardView(
                month,
                monthToDate,
                lastMonthSamePeriod,
                change,
                monthToDate.divide(BigDecimal.valueOf(today.getDayOfMonth()), 2, RoundingMode.HALF_UP),
                expenses.sumBetween(userId, today, today),
                expenses.totalsByCategory(userId, monthStart, today),
                expenses.totalsByPaymentMethod(userId, monthStart, today),
                trendLabels,
                trendValues,
                budgets.statusFor(userId, month),
                expenses.findByUserIdOrderByExpenseDateDescIdDesc(userId, PageRequest.of(0, 6)),
                insights.findByUserIdAndMonth(userId, month.toString()).orElse(null),
                expenses.countByUserId(userId));
    }
}
