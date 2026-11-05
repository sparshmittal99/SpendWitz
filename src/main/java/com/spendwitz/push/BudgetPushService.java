package com.spendwitz.push;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import com.spendwitz.budget.BudgetService;
import com.spendwitz.budget.BudgetStatus;
import com.spendwitz.common.Category;
import com.spendwitz.common.Money;
import com.spendwitz.expense.ExpenseChangedEvent;
import com.spendwitz.user.AppUser;
import com.spendwitz.user.AppUserRepository;

/**
 * Sends a phone notification the moment a category goes over its monthly budget: after an expense is saved (once
 * the transaction commits) or after budgets are lowered. Each category alerts once a month; if it drops back under
 * its limit and goes over again, it alerts again.
 */
@Service
public class BudgetPushService {

    private static final Logger log = LoggerFactory.getLogger(BudgetPushService.class);

    private final FcmSender fcm;
    private final PushTokenStore store;
    private final BudgetService budgets;
    private final AppUserRepository users;
    private final Clock clock;

    public BudgetPushService(FcmSender fcm, PushTokenStore store, BudgetService budgets, AppUserRepository users,
            Clock clock) {
        this.fcm = fcm;
        this.store = store;
        this.budgets = budgets;
        this.users = users;
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener
    public void onExpenseChanged(ExpenseChangedEvent event) {
        Map<String, Object> metadata = event.document().getMetadata();
        try {
            long userId = Long.parseLong(String.valueOf(metadata.get("userId")));
            Category category = Category.valueOf(String.valueOf(metadata.get("category")));
            check(userId, EnumSet.of(category));
        } catch (RuntimeException e) {
            log.warn("Budget alert check failed: {}", e.toString());
        }
    }

    /** After the budgets page is saved: a lowered limit can put a category over. */
    @Async
    public void onBudgetsSaved(long userId) {
        try {
            check(userId, EnumSet.allOf(Category.class));
        } catch (RuntimeException e) {
            log.warn("Budget alert check failed: {}", e.toString());
        }
    }

    void check(long userId, Set<Category> categories) {
        if (!fcm.enabled()) {
            return;
        }
        List<String> tokens = store.tokensFor(userId);
        if (tokens.isEmpty()) {
            return; // no phone with the app: nothing to send, and nothing marked as sent
        }
        YearMonth month = YearMonth.now(clock);
        String symbol = users.findById(userId).map(AppUser::getCurrency).map(Money::symbol).orElse("₹");

        for (BudgetStatus status : budgets.statusFor(userId, month)) {
            Category category = status.category();
            if (!categories.contains(category)) {
                continue;
            }
            if (!status.overBudget()) {
                store.clearAlertSent(userId, category, month);
            } else if (store.markAlertSent(userId, category, month) && !notify(tokens, status, symbol)) {
                store.clearAlertSent(userId, category, month); // nothing delivered: try again on the next save
            }
        }
    }

    private boolean notify(List<String> tokens, BudgetStatus status, String symbol) {
        String title = status.category().getLabel() + " is over budget";
        String body = "You've spent %s of your %s budget this month (%d%%)."
                .formatted(money(symbol, status.spent()), money(symbol, status.limit()), status.percentUsed());
        boolean delivered = false;
        for (String token : tokens) {
            switch (fcm.send(token, title, body, "budget-" + status.category().name(), Map.of("open_path", "/budgets"))) {
                case SENT -> delivered = true;
                case INVALID_TOKEN -> store.remove(token);
                case FAILED -> { }
            }
        }
        return delivered;
    }

    private static String money(String symbol, BigDecimal amount) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.of("en", "IN"));
        format.setMaximumFractionDigits(0);
        return symbol + format.format(amount);
    }
}
