package com.spendsense.expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.spendsense.common.Category;
import com.spendsense.common.PaymentMethod;

/** Every query is scoped by user id, so one user can never read another user's data. */
public interface ExpenseRepository extends JpaRepository<Expense, Long>, JpaSpecificationExecutor<Expense> {

    Optional<Expense> findByIdAndUserId(Long id, Long userId);

    @EntityGraph(attributePaths = "items")
    Optional<Expense> findWithItemsByIdAndUserId(Long id, Long userId);

    List<Expense> findByIdInAndUserId(Collection<Long> ids, Long userId);

    List<Expense> findByUserIdOrderByExpenseDateDescIdDesc(Long userId, Pageable pageable);

    Optional<Expense> findFirstByUserIdAndMerchantIgnoreCaseOrderByExpenseDateDesc(Long userId, String merchant);

    long countByUserId(Long userId);

    @Query("""
            select coalesce(sum(e.amount), 0) from Expense e
            where e.user.id = :userId and e.expenseDate between :from and :to""")
    BigDecimal sumBetween(@Param("userId") Long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select coalesce(sum(e.amount), 0) from Expense e
            where e.user.id = :userId and e.category = :category and e.expenseDate between :from and :to""")
    BigDecimal sumBetweenForCategory(@Param("userId") Long userId, @Param("category") Category category,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select new com.spendsense.expense.CategoryTotal(e.category, sum(e.amount), count(e)) from Expense e
            where e.user.id = :userId and e.expenseDate between :from and :to
            group by e.category order by sum(e.amount) desc""")
    List<CategoryTotal> totalsByCategory(@Param("userId") Long userId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("""
            select new com.spendsense.expense.MerchantTotal(e.merchant, sum(e.amount), count(e)) from Expense e
            where e.user.id = :userId and e.expenseDate between :from and :to
            group by e.merchant order by sum(e.amount) desc""")
    List<MerchantTotal> topMerchants(@Param("userId") Long userId, @Param("from") LocalDate from,
            @Param("to") LocalDate to, Pageable pageable);

    @Query("""
            select new com.spendsense.expense.PaymentMethodTotal(e.paymentMethod, sum(e.amount), count(e)) from Expense e
            where e.user.id = :userId and e.expenseDate between :from and :to
            group by e.paymentMethod order by sum(e.amount) desc""")
    List<PaymentMethodTotal> totalsByPaymentMethod(@Param("userId") Long userId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("""
            select coalesce(sum(e.amount), 0) from Expense e
            where e.user.id = :userId and e.paymentMethod = :method and e.expenseDate >= :from""")
    BigDecimal sumByPaymentMethodSince(@Param("userId") Long userId, @Param("method") PaymentMethod method,
            @Param("from") LocalDate from);

    List<Expense> findByUserIdAndPaymentMethodOrderByExpenseDateDescIdDesc(Long userId, PaymentMethod method,
            Pageable pageable);

    @Query(value = """
            select to_char(date_trunc('month', expense_date), 'YYYY-MM') as month, sum(amount) as total
            from expense
            where user_id = :userId and expense_date >= :from
            group by 1 order by 1""", nativeQuery = true)
    List<MonthTotal> monthlyTotals(@Param("userId") Long userId, @Param("from") LocalDate from);

    @Modifying
    @Query("delete from Expense e where e.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
