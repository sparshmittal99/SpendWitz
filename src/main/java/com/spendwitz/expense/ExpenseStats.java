package com.spendwitz.expense;

import java.math.BigDecimal;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** SUM and COUNT over any expense filter, computed by the database (never by the LLM). */
@Component
public class ExpenseStats {

    private final EntityManager em;

    public ExpenseStats(EntityManager em) {
        this.em = em;
    }

    public record Aggregate(BigDecimal total, long count) {
    }

    public record Group(Object key, BigDecimal total, long count) {
    }

    /** What a breakdown can be grouped by; values are Expense field names. */
    public enum GroupBy {
        CATEGORY("category"), MERCHANT("merchant"), PAYMENT_METHOD("paymentMethod");

        private final String field;

        GroupBy(String field) {
            this.field = field;
        }
    }

    /** SUM and COUNT per group for any filter, largest total first. */
    @Transactional(readOnly = true)
    public List<Group> breakdown(Specification<Expense> spec, GroupBy groupBy, int limit) {
        var cb = em.getCriteriaBuilder();
        var query = cb.createTupleQuery();
        var root = query.from(Expense.class);
        var key = root.get(groupBy.field);
        var sum = cb.sum(root.<BigDecimal>get("amount"));
        query.multiselect(key, sum, cb.count(root))
                .where(spec.toPredicate(root, query, cb))
                .groupBy(key)
                .orderBy(cb.desc(sum));
        return em.createQuery(query).setMaxResults(limit).getResultList().stream()
                .map(row -> new Group(row.get(0), row.get(1, BigDecimal.class), row.get(2, Long.class)))
                .toList();
    }

    @Transactional(readOnly = true)
    public Aggregate aggregate(Specification<Expense> spec) {
        var cb = em.getCriteriaBuilder();
        var query = cb.createTupleQuery();
        var root = query.from(Expense.class);
        query.multiselect(cb.sum(root.<BigDecimal>get("amount")), cb.count(root))
                .where(spec.toPredicate(root, query, cb));
        Tuple row = em.createQuery(query).getSingleResult();
        BigDecimal total = row.get(0, BigDecimal.class);
        return new Aggregate(total == null ? BigDecimal.ZERO : total, row.get(1, Long.class));
    }
}
