package com.spendwitz.expense;

import java.time.LocalDate;

import org.springframework.data.jpa.domain.Specification;

import com.spendwitz.common.Category;
import com.spendwitz.common.PaymentMethod;

/** Optional filters for the expense list page. */
public final class ExpenseSpecs {

    private ExpenseSpecs() {
    }

    public static Specification<Expense> filter(long userId, Category category, String query, LocalDate from, LocalDate to) {
        return filter(userId, category, null, query, from, to);
    }

    public static Specification<Expense> filter(long userId, Category category, PaymentMethod paymentMethod, String query,
            LocalDate from, LocalDate to) {
        return (root, cq, cb) -> {
            var predicate = cb.equal(root.get("user").get("id"), userId);
            if (category != null) {
                predicate = cb.and(predicate, cb.equal(root.get("category"), category));
            }
            if (paymentMethod != null) {
                predicate = cb.and(predicate, cb.equal(root.get("paymentMethod"), paymentMethod));
            }
            if (query != null && !query.isBlank()) {
                String like = "%" + query.trim().toLowerCase() + "%";
                predicate = cb.and(predicate, cb.or(
                        cb.like(cb.lower(root.get("merchant")), like),
                        cb.like(cb.lower(root.get("note")), like)));
            }
            if (from != null) {
                predicate = cb.and(predicate, cb.greaterThanOrEqualTo(root.get("expenseDate"), from));
            }
            if (to != null) {
                predicate = cb.and(predicate, cb.lessThanOrEqualTo(root.get("expenseDate"), to));
            }
            return predicate;
        };
    }
}
