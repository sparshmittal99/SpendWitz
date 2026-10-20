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
                // Also an item on the bill, e.g. "momos" finds the Swiggy order that had momos in it
                var items = cq.subquery(Long.class);
                var item = items.from(ExpenseItem.class);
                items.select(item.get("id")).where(cb.equal(item.get("expense"), root),
                        cb.like(cb.lower(item.get("name")), like));
                predicate = cb.and(predicate, cb.or(
                        cb.like(cb.lower(root.get("merchant")), like),
                        cb.like(cb.lower(root.get("note")), like),
                        cb.exists(items)));
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
