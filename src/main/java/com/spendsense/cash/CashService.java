package com.spendsense.cash;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spendsense.common.PaymentMethod;
import com.spendsense.expense.Expense;
import com.spendsense.expense.ExpenseRepository;

/**
 * Cash wallet: money in (ATM withdrawals, cash received) minus cash expenses. Tracking starts at the first
 * money-in entry, so cash spent before the user started recording withdrawals doesn't make the balance negative.
 */
@Service
public class CashService {

    private final CashTopUpRepository topUps;
    private final ExpenseRepository expenses;
    private final Clock clock;

    public CashService(CashTopUpRepository topUps, ExpenseRepository expenses, Clock clock) {
        this.topUps = topUps;
        this.expenses = expenses;
        this.clock = clock;
    }

    public record CashSummary(
            BigDecimal cashInHand,
            boolean tracking,
            BigDecimal spentThisMonth,
            BigDecimal spentToday,
            List<Expense> recentCashExpenses,
            List<CashTopUp> recentTopUps) {
    }

    @Transactional(readOnly = true)
    public CashSummary summary(long userId) {
        LocalDate today = LocalDate.now(clock);
        YearMonth month = YearMonth.from(today);
        LocalDate trackingStart = topUps.firstEntryDate(userId);
        BigDecimal cashInHand = BigDecimal.ZERO;
        if (trackingStart != null) {
            cashInHand = topUps.totalForUser(userId)
                    .subtract(expenses.sumByPaymentMethodSince(userId, PaymentMethod.CASH, trackingStart));
        }
        BigDecimal spentThisMonth = expenses.sumByPaymentMethodSince(userId, PaymentMethod.CASH, month.atDay(1));
        BigDecimal spentToday = expenses.sumByPaymentMethodSince(userId, PaymentMethod.CASH, today);
        return new CashSummary(
                cashInHand,
                trackingStart != null,
                spentThisMonth,
                spentToday,
                expenses.findByUserIdAndPaymentMethodOrderByExpenseDateDescIdDesc(userId, PaymentMethod.CASH,
                        PageRequest.of(0, 15)),
                topUps.findByUserIdOrderByEntryDateDescIdDesc(userId, PageRequest.of(0, 5)));
    }

    @Transactional
    public void addTopUp(long userId, BigDecimal amount, LocalDate date, String note) {
        topUps.save(new CashTopUp(userId, amount, date, note == null || note.isBlank() ? null : note.trim()));
    }

    @Transactional
    public void deleteTopUp(long userId, long id) {
        topUps.findByIdAndUserId(id, userId).ifPresent(topUps::delete);
    }
}
