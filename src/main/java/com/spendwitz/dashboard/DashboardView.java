package com.spendwitz.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;

import com.spendwitz.budget.BudgetStatus;
import com.spendwitz.expense.CategoryTotal;
import com.spendwitz.expense.Expense;
import com.spendwitz.expense.PaymentMethodTotal;

public record DashboardView(
        YearMonth month,
        BigDecimal monthToDate,
        BigDecimal lastMonthSamePeriod,
        Integer changePercent,
        BigDecimal dailyAverage,
        BigDecimal todayTotal,
        List<CategoryTotal> categories,
        List<PaymentMethodTotal> paymentMethods,
        List<String> trendLabels,
        List<Double> trendValues,
        List<BudgetStatus> budgets,
        List<Expense> recent,
        MonthlyInsight insight,
        long expenseCount) {

    /** Share of this month's spending paid this way, 0-100. */
    public int paymentShare(PaymentMethodTotal total) {
        return monthToDate.signum() == 0 ? 0
                : total.total().multiply(BigDecimal.valueOf(100))
                        .divide(monthToDate, 0, RoundingMode.HALF_UP).intValue();
    }

    public List<String> categoryLabels() {
        return categories.stream().map(c -> c.category().getLabel()).toList();
    }

    public List<Double> categoryValues() {
        return categories.stream().map(c -> c.total().doubleValue()).toList();
    }

    public List<String> categoryColors() {
        return categories.stream().map(c -> c.category().getColor()).toList();
    }
}
