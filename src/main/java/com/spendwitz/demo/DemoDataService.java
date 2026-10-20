package com.spendwitz.demo;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.spendwitz.ai.ExpenseIndexer;
import com.spendwitz.budget.Budget;
import com.spendwitz.budget.BudgetRepository;
import com.spendwitz.cash.CashTopUp;
import com.spendwitz.cash.CashTopUpRepository;
import com.spendwitz.common.Category;
import com.spendwitz.common.PaymentMethod;
import com.spendwitz.config.AppProperties;
import com.spendwitz.dashboard.MonthlyInsightRepository;
import com.spendwitz.expense.Expense;
import com.spendwitz.expense.ExpenseItem;
import com.spendwitz.expense.ExpenseRepository;
import com.spendwitz.expense.ExpenseSource;
import com.spendwitz.user.AppUser;
import com.spendwitz.user.AppUserRepository;

/**
 * Maintains a public demo account with ~6 months of realistic expenses, so visitors can try every feature
 * without signing up. The data is rebuilt nightly, which also undoes whatever visitors changed.
 */
@Service
public class DemoDataService {

    private static final Logger log = LoggerFactory.getLogger(DemoDataService.class);
    private static final int DAYS_OF_HISTORY = 185;

    private final AppProperties.Demo demo;
    private final AppUserRepository users;
    private final ExpenseRepository expenses;
    private final BudgetRepository budgets;
    private final MonthlyInsightRepository insights;
    private final CashTopUpRepository cashTopUps;
    private final ExpenseIndexer indexer;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DemoDataService(AppProperties props, AppUserRepository users, ExpenseRepository expenses,
            BudgetRepository budgets, MonthlyInsightRepository insights, CashTopUpRepository cashTopUps,
            ExpenseIndexer indexer,
            PasswordEncoder passwordEncoder, TransactionTemplate tx, Clock clock) {
        this.demo = props.demo();
        this.users = users;
        this.expenses = expenses;
        this.budgets = budgets;
        this.insights = insights;
        this.cashTopUps = cashTopUps;
        this.indexer = indexer;
        this.passwordEncoder = passwordEncoder;
        this.tx = tx;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        if (!demo.enabled()) {
            return;
        }
        AppUser user = users.findByEmailIgnoreCase(demo.email()).orElseGet(() -> users.save(
                new AppUser(demo.email(), passwordEncoder.encode(demo.password()), "Demo User", "INR", true)));
        long expenseCount = expenses.countByUserId(user.getId());
        // Also rebuild if a previous run was stopped before every expense got its embedding
        if (expenseCount == 0 || indexer.countForUser(user.getId()) < expenseCount) {
            reset(user.getId());
        }
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "${spendwitz.zone}")
    public void nightlyReset() {
        if (demo.enabled()) {
            users.findByEmailIgnoreCase(demo.email()).ifPresent(u -> reset(u.getId()));
        }
    }

    private void reset(long userId) {
        List<Document> documents = tx.execute(status -> {
            expenses.deleteAllByUserId(userId);
            budgets.deleteAllByUserId(userId);
            insights.deleteAllByUserId(userId);
            cashTopUps.deleteAllByUserId(userId);
            cashTopUps.saveAll(cashWithdrawals(userId, LocalDate.now(clock)));
            AppUser user = users.getReferenceById(userId);
            List<Expense> seeded = expenses.saveAll(generate(user, LocalDate.now(clock)));
            budgets.saveAll(List.of(
                    new Budget(userId, Category.FOOD_DINING, new BigDecimal("6000")),
                    new Budget(userId, Category.GROCERIES, new BigDecimal("7000")),
                    new Budget(userId, Category.TRANSPORT, new BigDecimal("3500")),
                    new Budget(userId, Category.SHOPPING, new BigDecimal("4000")),
                    new Budget(userId, Category.ENTERTAINMENT, new BigDecimal("1500"))));
            return seeded.stream().map(ExpenseIndexer::toDocument).toList();
        });
        log.info("Demo account reset with {} expenses; indexing embeddings in the background", documents.size());

        // Embeddings are slow and rate-limited, so build them off the startup thread.
        Thread.ofVirtual().name("demo-indexer").start(() -> {
            try {
                indexer.removeAllForUser(userId);
                indexer.indexAll(documents);
                log.info("Demo embeddings ready");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Demo embeddings failed (is GEMINI_API_KEY set?): {}", e.getMessage());
            }
        });
    }

    /** Deterministic but realistic-looking spending for a young professional in Bengaluru. */
    static List<Expense> generate(AppUser user, LocalDate today) {
        Random rnd = new Random(42);
        List<Expense> list = new ArrayList<>();
        LocalDate start = today.minusDays(DAYS_OF_HISTORY);

        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            int dom = d.getDayOfMonth();
            DayOfWeek dow = d.getDayOfWeek();
            boolean weekday = dow.getValue() <= 5;
            boolean currentMonth = d.getMonth() == today.getMonth() && d.getYear() == today.getYear();

            switch (dom) {
                case 1 -> list.add(paid(e(user, d, "Landlord", 15000, Category.RENT, "Monthly rent"), PaymentMethod.NET_BANKING));
                case 3 -> list.add(e(user, d, "Airtel", 499, Category.BILLS_UTILITIES, "Postpaid mobile plan"));
                case 5 -> list.add(paid(e(user, d, "Netflix", 649, Category.ENTERTAINMENT, null), PaymentMethod.CARD));
                case 7 -> list.add(paid(e(user, d, "Cult.fit", 1499, Category.HEALTH, "Gym membership"), PaymentMethod.CARD));
                case 10 -> list.add(e(user, d, "BESCOM", between(rnd, 1100, 1900), Category.BILLS_UTILITIES, "Electricity bill"));
                case 12 -> list.add(e(user, d, "ACT Fibernet", 1049, Category.BILLS_UTILITIES, "Home broadband"));
                case 14 -> list.add(paid(e(user, d, "Amazon", between(rnd, 499, 2999), Category.SHOPPING,
                        pick(rnd, "Wireless earbuds", "Kitchen stuff", "Books", "Phone cover and charger")), PaymentMethod.CARD));
                case 15 -> list.add(paid(e(user, d, "Press wala", 180, Category.OTHER, "Ironing clothes"), PaymentMethod.CASH));
                case 18 -> list.add(e(user, d, "Apollo Pharmacy", between(rnd, 240, 780), Category.HEALTH, "Medicines"));
                case 20 -> list.add(paid(e(user, d, "PVR Cinemas", 640, Category.ENTERTAINMENT, "Movie night with friends"), PaymentMethod.CARD));
                case 25 -> list.add(paid(e(user, d, "Toit Brewpub", between(rnd, 1400, 2400), Category.FOOD_DINING, "Team dinner"), PaymentMethod.CARD));
                case 27 -> list.add(paid(e(user, d, "Myntra", between(rnd, 799, 3499), Category.SHOPPING,
                        pick(rnd, "Running shoes", "Birthday gift for Riya", "Winter jacket", "T-shirts")), PaymentMethod.CARD));
                case 2 -> list.add(paid(e(user, d, "Doodhwala", 1860, Category.GROCERIES, "Milk for the month"), PaymentMethod.CASH));
                default -> { }
            }

            // Food delivery: more often this month, so the dashboard has a story to tell.
            boolean deliveryDay = dow == DayOfWeek.TUESDAY || dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SUNDAY
                    || (currentMonth && (dow == DayOfWeek.MONDAY || dow == DayOfWeek.THURSDAY));
            if (deliveryDay) {
                list.add(e(user, d, pick(rnd, "Swiggy", "Zomato"), between(rnd, 220, 650), Category.FOOD_DINING,
                        pick(rnd, null, "Dinner", "Biryani", "Late night pizza", "Lunch at desk")));
            }
            if (dow == DayOfWeek.MONDAY || dow == DayOfWeek.WEDNESDAY) {
                list.add(e(user, d, pick(rnd, "Third Wave Coffee", "Third Wave Coffee", "Starbucks"),
                        between(rnd, 260, 420), Category.FOOD_DINING, pick(rnd, "Coffee near office", null, "Coffee with Arjun")));
            }
            if (weekday && rnd.nextDouble() < 0.5) {
                list.add(paid(e(user, d, pick(rnd, "Uber", "Ola", "Rapido"), between(rnd, 90, 380), Category.TRANSPORT,
                        pick(rnd, "Office commute", null, "Ride home")), rnd.nextDouble() < 0.25 ? PaymentMethod.CASH : PaymentMethod.UPI));
            }

            // Everyday cash spending with no bill: tea stall, vegetable vendor, autos, kirana
            if (weekday && rnd.nextDouble() < 0.6) {
                list.add(paid(e(user, d, "Chai", between(rnd, 20, 30), Category.FOOD_DINING,
                        pick(rnd, null, "Tea stall near office", "Chai and samosa")), PaymentMethod.CASH));
            }
            if (dow == DayOfWeek.WEDNESDAY || dow == DayOfWeek.SUNDAY) {
                list.add(paid(e(user, d, "Sabzi", between(rnd, 120, 260), Category.GROCERIES,
                        pick(rnd, "Vegetables from the cart", "Sabzi mandi", null)), PaymentMethod.CASH));
            }
            if ((dow == DayOfWeek.TUESDAY || dow == DayOfWeek.THURSDAY) && rnd.nextDouble() < 0.8) {
                list.add(paid(e(user, d, "Auto", between(rnd, 60, 150), Category.TRANSPORT,
                        pick(rnd, "Auto to metro", "Auto to market", null)), PaymentMethod.CASH));
            }
            if (dow == DayOfWeek.SATURDAY && d.getDayOfYear() % 2 == 0) {
                list.add(paid(e(user, d, "Sharma Kirana Store", between(rnd, 150, 420), Category.GROCERIES,
                        pick(rnd, "Atta and oil", "Biscuits, soap, detergent", "Dal and rice")), PaymentMethod.CASH));
            }
            if (dow == DayOfWeek.SATURDAY && rnd.nextDouble() < 0.4) {
                list.add(paid(e(user, d, "Pani puri", between(rnd, 40, 70), Category.FOOD_DINING, null), PaymentMethod.CASH));
            }
            if (dow == DayOfWeek.SATURDAY) {
                list.add(groceries(user, d, rnd));
            }
        }

        list.add(paid(e(user, today.minusDays(40), "Udemy", 499, Category.EDUCATION, "Spring Boot microservices course"), PaymentMethod.CARD));
        list.add(paid(e(user, today.minusDays(58), "IndiGo", 6240, Category.TRAVEL, "Flights for Goa trip"), PaymentMethod.CARD));
        list.add(e(user, today.minusDays(55), "Zostel Goa", 3600, Category.TRAVEL, "Goa trip stay, 3 nights"));
        list.add(paid(e(user, today.minusDays(54), "Britto's", 1850, Category.FOOD_DINING, "Seafood dinner in Goa"), PaymentMethod.CASH));
        return list;
    }

    private static Expense groceries(AppUser user, LocalDate d, Random rnd) {
        String store = pick(rnd, "BigBasket", "DMart", "Blinkit");
        List<ExpenseItem> items = new ArrayList<>();
        Map<String, Integer> catalogue = Map.of("Milk 1L", 68, "Eggs (12)", 96, "Basmati rice 5kg", 545,
                "Toor dal 1kg", 165, "Bananas", 60, "Bread", 50, "Paneer 200g", 95, "Coffee powder", 310);
        int total = 0;
        for (var entry : catalogue.entrySet()) {
            if (rnd.nextDouble() < 0.6) {
                int qty = 1 + rnd.nextInt(2);
                items.add(new ExpenseItem(entry.getKey(), BigDecimal.valueOf(qty), BigDecimal.valueOf((long) entry.getValue() * qty)));
                total += entry.getValue() * qty;
            }
        }
        Expense expense = paid(e(user, d, store, Math.max(total, 250), Category.GROCERIES, "Weekly groceries"),
                store.equals("DMart") ? PaymentMethod.CARD : PaymentMethod.UPI);
        expense.replaceItems(items);
        return expense;
    }

    private static Expense e(AppUser user, LocalDate date, String merchant, int amount, Category category, String note) {
        Expense expense = new Expense(user, ExpenseSource.MANUAL);
        expense.update(merchant, BigDecimal.valueOf(amount), category, date, note);
        return expense;
    }

    private static Expense paid(Expense expense, PaymentMethod method) {
        expense.setPaymentMethod(method);
        return expense;
    }

    /** ATM withdrawals on the 1st and 16th, so the demo has a realistic cash-in-hand balance. */
    static List<CashTopUp> cashWithdrawals(long userId, LocalDate today) {
        List<CashTopUp> list = new ArrayList<>();
        for (LocalDate d = today.minusDays(DAYS_OF_HISTORY); !d.isAfter(today); d = d.plusDays(1)) {
            if (d.getDayOfMonth() == 1 || d.getDayOfMonth() == 16) {
                String amount = d.getDayOfMonth() == 1 ? "3500" : "3000";
                list.add(new CashTopUp(userId, new BigDecimal(amount), d, "ATM withdrawal"));
            }
        }
        return list;
    }

    private static int between(Random rnd, int min, int max) {
        return min + rnd.nextInt(max - min + 1);
    }

    @SafeVarargs
    private static <T> T pick(Random rnd, T... options) {
        return options[rnd.nextInt(options.length)];
    }
}
