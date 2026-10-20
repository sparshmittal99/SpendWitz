package com.spendwitz.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.spendwitz.IntegrationTest;
import com.spendwitz.ai.ExpenseIndexer;
import com.spendwitz.budget.BudgetService;
import com.spendwitz.common.Category;
import com.spendwitz.expense.ExpenseStats;
import com.spendwitz.user.AuthUser;

class ExpenseToolsTest extends IntegrationTest {

    @Autowired
    BudgetService budgets;

    @Autowired
    ExpenseIndexer indexer;

    @Autowired
    ExpenseStats stats;

    @Autowired
    Clock clock;

    AuthUser alice;
    AuthUser bob;
    LocalDate today;

    @BeforeEach
    void setUp() {
        alice = newUser();
        bob = newUser();
        today = LocalDate.now(clock);
    }

    ExpenseTools toolsFor(AuthUser user) {
        return new ExpenseTools(user.id(), expenses, stats, budgets, indexer, clock);
    }

    @Test
    void totalsOnlyIncludeTheBoundUsersExpenses() {
        addExpense(alice, "Swiggy", "300.00", Category.FOOD_DINING, today);
        addExpense(alice, "Uber", "150.00", Category.TRANSPORT, today);
        addExpense(bob, "Swiggy", "9999.00", Category.FOOD_DINING, today);

        var all = toolsFor(alice).getTotalSpending(today.toString(), today.toString(), null, null, null);
        var food = toolsFor(alice).getTotalSpending(today.toString(), today.toString(), "FOOD_DINING", null, null);

        assertThat(all.total()).isEqualByComparingTo("450.00");
        assertThat(food.total()).isEqualByComparingTo("300.00");
    }

    @Test
    void findExpensesFiltersByMerchantText() {
        addExpense(alice, "Swiggy", "300.00", Category.FOOD_DINING, today);
        addExpense(alice, "Zomato", "200.00", Category.FOOD_DINING, today);

        var result = toolsFor(alice).findExpenses(today.minusDays(1).toString(), today.toString(), null, "swig", null);

        assertThat(result.expenses()).extracting(ExpenseTools.ExpenseRow::merchant).containsExactly("Swiggy");
        assertThat(result.matchingTotal()).isEqualByComparingTo("300.00");
    }

    @Test
    void totalsByMerchantAreComputedInSqlEvenWhenTheListIsTruncated() {
        for (int i = 0; i < 30; i++) {
            addExpense(alice, "Zomato", "100.50", Category.FOOD_DINING, today.minusDays(i));
        }
        String from = today.minusDays(40).toString();

        var total = toolsFor(alice).getTotalSpending(from, today.toString(), null, "zomato", null);
        var found = toolsFor(alice).findExpenses(from, today.toString(), null, "zomato", null);

        assertThat(total.total()).isEqualByComparingTo("3015.00");
        assertThat(total.count()).isEqualTo(30);
        assertThat(found.expenses()).hasSize(25);
        assertThat(found.truncated()).isTrue();
        assertThat(found.matchingTotal()).isEqualByComparingTo("3015.00");
    }

    @Test
    void cashSpendingCanBeFilteredByPaymentMethod() {
        var tea = addExpense(alice, "Chai", "20.00", Category.FOOD_DINING, today);
        tea.setPaymentMethod(com.spendwitz.common.PaymentMethod.CASH);
        expenses.save(tea);
        addExpense(alice, "Swiggy", "300.00", Category.FOOD_DINING, today);

        var cash = toolsFor(alice).getTotalSpending(today.toString(), today.toString(), null, null, "cash");
        var split = toolsFor(alice).getSpendingByPaymentMethod(today.toString(), today.toString(), null);
        var cashByCategory = toolsFor(alice).getSpendingByCategory(today.toString(), today.toString(), "CASH", null);
        var cashMerchants = toolsFor(alice).getTopMerchants(today.toString(), today.toString(), null, null, "CASH");

        assertThat(cash.total()).isEqualByComparingTo("20.00");
        assertThat(split).extracting(ExpenseTools.PaymentMethodRow::paymentMethod).containsExactly("UPI", "CASH");
        assertThat(cashByCategory).singleElement().satisfies(row -> {
            assertThat(row.category()).isEqualTo("FOOD_DINING");
            assertThat(row.total()).isEqualByComparingTo("20.00");
        });
        assertThat(cashMerchants).extracting(ExpenseTools.MerchantRow::merchant).containsExactly("Chai");
    }

    @Test
    void monthlyTotalsIncludeEmptyMonths() {
        addExpense(alice, "Rent", "15000.00", Category.RENT, today.withDayOfMonth(1));

        var rows = toolsFor(alice).getMonthlyTotals(3);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).total()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(rows.get(2).month()).isEqualTo(YearMonth.from(today).toString());
        assertThat(rows.get(2).total()).isEqualByComparingTo("15000.00");
    }

    @Test
    void categoryNamesFromTheModelAreParsedLeniently() {
        addExpense(alice, "PVR", "640.00", Category.ENTERTAINMENT, today);

        var total = toolsFor(alice).getTotalSpending(today.toString(), today.toString(), "entertainment", null, null);

        assertThat(total.total()).isEqualByComparingTo("640.00");
    }

    @Test
    void invisibleCharactersInDatesAreIgnored() {
        addExpense(alice, "Uber", "150.00", Category.TRANSPORT, today);

        var total = toolsFor(alice).getTotalSpending(today + "\u200d", " " + today, null, null, null);

        assertThat(total.total()).isEqualByComparingTo("150.00");
    }

    @Test
    void badArgumentsProduceErrorsTheModelCanCorrect() {
        var tools = toolsFor(alice);
        assertThatThrownBy(() -> tools.getTotalSpending("last month", today.toString(), null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("yyyy-MM-dd");
        assertThatThrownBy(() -> tools.getTotalSpending(today.toString(), today.minusDays(1).toString(), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.getTotalSpending(today.toString(), today.toString(), "CRYPTO", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown category");
    }
}
