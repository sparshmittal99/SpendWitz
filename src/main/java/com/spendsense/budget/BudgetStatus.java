package com.spendsense.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.spendsense.common.Category;

public record BudgetStatus(Category category, BigDecimal limit, BigDecimal spent) {

    public BigDecimal remaining() {
        return limit.subtract(spent);
    }

    public int percentUsed() {
        if (limit.signum() == 0) {
            return 0;
        }
        return spent.multiply(BigDecimal.valueOf(100)).divide(limit, 0, RoundingMode.HALF_UP).intValue();
    }

    public boolean overBudget() {
        return spent.compareTo(limit) > 0;
    }

    /** Bootstrap colour for the progress bar. */
    public String level() {
        int pct = percentUsed();
        return pct >= 100 ? "danger" : pct >= 80 ? "warning" : "success";
    }
}
