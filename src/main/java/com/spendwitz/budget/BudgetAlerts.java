package com.spendwitz.budget;

import java.math.BigDecimal;
import java.util.List;

/** JSON for the Android app's over-budget notifications: every budgeted category this month. */
public record BudgetAlerts(String month, String currencySymbol, List<Item> categories) {

    public record Item(String code, String label, BigDecimal limit, BigDecimal spent, int percentUsed,
            boolean overBudget) {
    }
}
