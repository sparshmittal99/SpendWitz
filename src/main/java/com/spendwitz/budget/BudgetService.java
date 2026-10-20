package com.spendwitz.budget;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spendwitz.common.Category;
import com.spendwitz.expense.CategoryTotal;
import com.spendwitz.expense.ExpenseRepository;

@Service
public class BudgetService {

    private final BudgetRepository budgets;
    private final ExpenseRepository expenses;

    public BudgetService(BudgetRepository budgets, ExpenseRepository expenses) {
        this.budgets = budgets;
        this.expenses = expenses;
    }

    @Transactional(readOnly = true)
    public List<BudgetStatus> statusFor(long userId, YearMonth month) {
        Map<Category, BigDecimal> spent = expenses
                .totalsByCategory(userId, month.atDay(1), month.atEndOfMonth()).stream()
                .collect(Collectors.toMap(CategoryTotal::category, CategoryTotal::total));
        return budgets.findByUserIdOrderByCategory(userId).stream()
                .map(b -> new BudgetStatus(b.getCategory(), b.getMonthlyLimit(),
                        spent.getOrDefault(b.getCategory(), BigDecimal.ZERO)))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<Category, BigDecimal> limits(long userId) {
        Map<Category, BigDecimal> limits = new EnumMap<>(Category.class);
        budgets.findByUserIdOrderByCategory(userId).forEach(b -> limits.put(b.getCategory(), b.getMonthlyLimit()));
        return limits;
    }

    /** Sets, changes or (for null/zero) removes the limit of each category. */
    @Transactional
    public void save(long userId, Map<Category, BigDecimal> newLimits) {
        Map<Category, Budget> existing = budgets.findByUserIdOrderByCategory(userId).stream()
                .collect(Collectors.toMap(Budget::getCategory, Function.identity()));
        for (Category category : Category.values()) {
            BigDecimal limit = newLimits.get(category);
            Budget budget = existing.get(category);
            boolean hasLimit = limit != null && limit.signum() > 0;
            if (budget != null && !hasLimit) {
                budgets.delete(budget);
            } else if (budget != null) {
                budget.setMonthlyLimit(limit);
            } else if (hasLimit) {
                budgets.save(new Budget(userId, category, limit));
            }
        }
    }
}
