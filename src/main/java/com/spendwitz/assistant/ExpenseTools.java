package com.spendwitz.assistant;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.spendwitz.ai.ExpenseIndexer;
import com.spendwitz.budget.BudgetService;
import com.spendwitz.common.Category;
import com.spendwitz.common.PaymentMethod;
import com.spendwitz.expense.Expense;
import com.spendwitz.expense.ExpenseRepository;
import com.spendwitz.expense.ExpenseSpecs;
import com.spendwitz.expense.ExpenseStats;
import com.spendwitz.expense.MonthTotal;

/**
 * The only way the assistant can touch data. A new instance is created per request and bound to the
 * logged-in user's id, so the model never sees or chooses a user id: it can only read this user's data,
 * through fixed, read-only, parameterised queries. No model-written SQL.
 */
public class ExpenseTools {

    private static final int MAX_ROWS = 25;
    private static final int MAX_RANGE_DAYS = 3 * 366;

    private final long userId;
    private final ExpenseRepository expenses;
    private final ExpenseStats stats;
    private final BudgetService budgets;
    private final ExpenseIndexer indexer;
    private final Clock clock;

    public ExpenseTools(long userId, ExpenseRepository expenses, ExpenseStats stats, BudgetService budgets,
            ExpenseIndexer indexer, Clock clock) {
        this.userId = userId;
        this.expenses = expenses;
        this.stats = stats;
        this.budgets = budgets;
        this.indexer = indexer;
        this.clock = clock;
    }

    public record TotalResult(String from, String to, String category, String text, String paymentMethod,
            BigDecimal total, long count) {
    }

    public record PaymentMethodRow(String paymentMethod, BigDecimal total, long count) {
    }

    public record FindResult(long matchingCount, BigDecimal matchingTotal, boolean truncated, List<ExpenseRow> expenses) {
    }

    public record CategoryRow(String category, String label, BigDecimal total, long count) {
    }

    public record MerchantRow(String merchant, BigDecimal total, long count) {
    }

    public record MonthRow(String month, BigDecimal total) {
    }

    public record ExpenseRow(long id, String date, String merchant, BigDecimal amount, String category,
            String paymentMethod, String note) {

        static ExpenseRow of(Expense e) {
            return new ExpenseRow(e.getId(), e.getExpenseDate().toString(), e.getMerchant(), e.getAmount(),
                    e.getCategory().name(), e.getPaymentMethod().name(), e.getNote());
        }
    }

    public record BudgetRow(String category, BigDecimal limit, BigDecimal spent, BigDecimal remaining, int percentUsed) {
    }

    @Tool(description = "Exact total amount and number of expenses between two dates (both inclusive). "
            + "Optionally filter by category, text in the merchant name or note, and/or payment method. "
            + "Use this for every total or count, e.g. call it once per merchant to compare merchants.")
    public TotalResult getTotalSpending(
            @ToolParam(description = "Start date, yyyy-MM-dd") String from,
            @ToolParam(description = "End date (inclusive), yyyy-MM-dd") String to,
            @ToolParam(description = "Optional category code such as FOOD_DINING. Omit for all categories.", required = false) String category,
            @ToolParam(description = "Optional text to match in merchant name or note, e.g. Swiggy", required = false) String text,
            @ToolParam(description = "Optional payment method: UPI, CASH, CARD, NET_BANKING or OTHER", required = false) String paymentMethod) {
        LocalDate start = date(from);
        LocalDate end = endDate(start, to);
        Category cat = optionalCategory(category);
        PaymentMethod method = optionalPaymentMethod(paymentMethod);
        var result = stats.aggregate(ExpenseSpecs.filter(userId, cat, method, text, start, end));
        return new TotalResult(start.toString(), end.toString(), cat == null ? "ALL" : cat.name(),
                text == null || text.isBlank() ? null : text, method == null ? "ALL" : method.name(),
                result.total(), result.count());
    }

    @Tool(description = "Spending split by payment method (UPI, CASH, CARD, ...) between two dates (both inclusive).")
    public List<PaymentMethodRow> getSpendingByPaymentMethod(
            @ToolParam(description = "Start date, yyyy-MM-dd") String from,
            @ToolParam(description = "End date (inclusive), yyyy-MM-dd") String to,
            @ToolParam(description = "Optional category code such as GROCERIES", required = false) String category) {
        LocalDate start = date(from);
        var spec = ExpenseSpecs.filter(userId, optionalCategory(category), null, null, start, endDate(start, to));
        return stats.breakdown(spec, ExpenseStats.GroupBy.PAYMENT_METHOD, 10).stream()
                .map(g -> new PaymentMethodRow(((PaymentMethod) g.key()).name(), g.total(), g.count()))
                .toList();
    }

    @Tool(description = "Exact spending per category between two dates (both inclusive), largest first. "
            + "Optionally only for one payment method (e.g. CASH) and/or text in the merchant name or note. "
            + "Use this for any per-category breakdown instead of adding up expenses yourself.")
    public List<CategoryRow> getSpendingByCategory(
            @ToolParam(description = "Start date, yyyy-MM-dd") String from,
            @ToolParam(description = "End date (inclusive), yyyy-MM-dd") String to,
            @ToolParam(description = "Optional payment method: UPI, CASH, CARD, NET_BANKING or OTHER", required = false) String paymentMethod,
            @ToolParam(description = "Optional text to match in merchant name or note", required = false) String text) {
        LocalDate start = date(from);
        var spec = ExpenseSpecs.filter(userId, null, optionalPaymentMethod(paymentMethod), text, start, endDate(start, to));
        return stats.breakdown(spec, ExpenseStats.GroupBy.CATEGORY, 50).stream()
                .map(g -> {
                    Category c = (Category) g.key();
                    return new CategoryRow(c.name(), c.getLabel(), g.total(), g.count());
                })
                .toList();
    }

    @Tool(description = "Exact spending per merchant between two dates (both inclusive), largest first. "
            + "Optionally only for one category and/or payment method. "
            + "Use this for any per-merchant breakdown instead of adding up expenses yourself.")
    public List<MerchantRow> getTopMerchants(
            @ToolParam(description = "Start date, yyyy-MM-dd") String from,
            @ToolParam(description = "End date (inclusive), yyyy-MM-dd") String to,
            @ToolParam(description = "How many merchants to return, 1-30. Default 10.", required = false) Integer limit,
            @ToolParam(description = "Optional category code such as GROCERIES", required = false) String category,
            @ToolParam(description = "Optional payment method: UPI, CASH, CARD, NET_BANKING or OTHER", required = false) String paymentMethod) {
        LocalDate start = date(from);
        int n = limit == null ? 10 : Math.clamp(limit, 1, 30);
        var spec = ExpenseSpecs.filter(userId, optionalCategory(category), optionalPaymentMethod(paymentMethod), null,
                start, endDate(start, to));
        return stats.breakdown(spec, ExpenseStats.GroupBy.MERCHANT, n).stream()
                .map(g -> new MerchantRow((String) g.key(), g.total(), g.count()))
                .toList();
    }

    @Tool(description = "Total spending per calendar month for the last N months, including the current month. "
            + "Months with no spending are included with total 0.")
    public List<MonthRow> getMonthlyTotals(
            @ToolParam(description = "Number of months, 1-24") int months) {
        int n = Math.clamp(months, 1, 24);
        YearMonth current = YearMonth.now(clock);
        YearMonth first = current.minusMonths(n - 1L);
        Map<String, BigDecimal> totals = expenses.monthlyTotals(userId, first.atDay(1)).stream()
                .collect(Collectors.toMap(MonthTotal::getMonth, MonthTotal::getTotal));
        List<MonthRow> rows = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(current); m = m.plusMonths(1)) {
            rows.add(new MonthRow(m.toString(), totals.getOrDefault(m.toString(), BigDecimal.ZERO)));
        }
        return rows;
    }

    @Tool(description = "List individual expenses between two dates (both inclusive), newest first, at most 25 rows. "
            + "Optionally filter by category, text contained in the merchant name or note, and/or payment method. "
            + "matchingCount and matchingTotal cover ALL matching expenses, even when the list is truncated.")
    public FindResult findExpenses(
            @ToolParam(description = "Start date, yyyy-MM-dd") String from,
            @ToolParam(description = "End date (inclusive), yyyy-MM-dd") String to,
            @ToolParam(description = "Optional category code such as TRANSPORT", required = false) String category,
            @ToolParam(description = "Optional text to match in merchant name or note, e.g. Swiggy", required = false) String text,
            @ToolParam(description = "Optional payment method: UPI, CASH, CARD, NET_BANKING or OTHER", required = false) String paymentMethod) {
        LocalDate start = date(from);
        var spec = ExpenseSpecs.filter(userId, optionalCategory(category), optionalPaymentMethod(paymentMethod), text,
                start, endDate(start, to));
        var page = PageRequest.of(0, MAX_ROWS, Sort.by(Sort.Order.desc("expenseDate"), Sort.Order.desc("id")));
        var aggregate = stats.aggregate(spec);
        List<ExpenseRow> rows = expenses.findAll(spec, page).map(ExpenseRow::of).getContent();
        return new FindResult(aggregate.count(), aggregate.total(), aggregate.count() > rows.size(), rows);
    }

    @Tool(description = "Budget status for one month: limit, amount spent, remaining and percent used per category "
            + "that has a budget. Empty if the user has set no budgets.")
    public List<BudgetRow> getBudgetStatus(
            @ToolParam(description = "Month as yyyy-MM") String month) {
        YearMonth ym;
        try {
            ym = YearMonth.parse(month.replaceAll("[^0-9-]", ""));
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("month must be yyyy-MM, got: " + month);
        }
        return budgets.statusFor(userId, ym).stream()
                .map(s -> new BudgetRow(s.category().name(), s.limit(), s.spent(), s.remaining(), s.percentUsed()))
                .toList();
    }

    @Tool(description = "Search the user's expenses by meaning rather than exact words, for vague descriptions like "
            + "'that coffee place near the office', 'gifts' or 'the Goa trip'. Returns the closest matches.")
    public List<ExpenseRow> searchExpenses(
            @ToolParam(description = "Natural-language description of the expense(s)") String description) {
        var groups = indexer.searchGrouped(userId, description, 8, 0.6);
        Map<Long, Expense> byId = expenses.findByIdInAndUserId(
                        groups.stream().flatMap(g -> g.expenseIds().stream()).toList(), userId).stream()
                .collect(Collectors.toMap(Expense::getId, Function.identity(), (a, b) -> a, HashMap::new));
        // Up to 3 most recent expenses per group of look-alikes, so distinct matches aren't crowded out
        return groups.stream()
                .flatMap(g -> g.expenseIds().stream()
                        .map(byId::get)
                        .filter(e -> e != null)
                        .sorted(Comparator.comparing(Expense::getExpenseDate).reversed())
                        .limit(3))
                .map(ExpenseRow::of)
                .toList();
    }

    private static LocalDate date(String value) {
        try {
            // models occasionally emit invisible characters (e.g. U+200D) inside arguments
            return LocalDate.parse(value.replaceAll("[^0-9-]", ""));
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("Dates must be yyyy-MM-dd, got: " + value);
        }
    }

    private static LocalDate endDate(LocalDate start, String to) {
        LocalDate end = date(to);
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("End date is before start date.");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw new IllegalArgumentException("Date range too large; use at most 3 years.");
        }
        return end;
    }

    private static PaymentMethod optionalPaymentMethod(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("ALL")) {
            return null;
        }
        return PaymentMethod.parse(value).orElseThrow(() -> new IllegalArgumentException(
                "Unknown payment method " + value + ". Use one of: " + PaymentMethod.promptList()));
    }

    private static Category optionalCategory(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("ALL")) {
            return null;
        }
        return Category.parse(value).orElseThrow(
                () -> new IllegalArgumentException("Unknown category " + value + ". Use one of: " + Category.promptList()));
    }
}
