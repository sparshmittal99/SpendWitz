package com.spendwitz.web;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.spendwitz.ai.ExpenseIndexer;
import com.spendwitz.expense.Expense;
import com.spendwitz.expense.ExpenseRepository;
import com.spendwitz.user.AuthUser;

/** Semantic search: matches by meaning ("coffee near office") using pgvector, not by keyword. */
@Controller
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    private static final double MIN_SIMILARITY = 0.6;

    private final ExpenseIndexer indexer;
    private final ExpenseRepository expenses;

    public SearchController(ExpenseIndexer indexer, ExpenseRepository expenses) {
        this.indexer = indexer;
        this.expenses = expenses;
    }

    /** The most recent expense of a group of look-alikes, plus how many others are like it. */
    public record Result(Expense expense, int matchPercent, int similarCount) {
    }

    @GetMapping("/search")
    public String search(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) String q,
            Model model) {
        model.addAttribute("q", q);
        if (q == null || q.isBlank()) {
            return "search";
        }
        if (q.length() > 200) {
            model.addAttribute("error", "Keep the search under 200 characters.");
            return "search";
        }
        try {
            var groups = indexer.searchGrouped(user.id(), q.trim(), 10, MIN_SIMILARITY);
            Map<Long, Expense> byId = expenses
                    .findByIdInAndUserId(groups.stream().flatMap(g -> g.expenseIds().stream()).toList(), user.id())
                    .stream().collect(Collectors.toMap(Expense::getId, Function.identity()));
            List<Result> results = groups.stream()
                    .map(g -> {
                        List<Expense> found = g.expenseIds().stream().map(byId::get).filter(Objects::nonNull).toList();
                        return found.stream()
                                .max(Comparator.comparing(Expense::getExpenseDate))
                                .map(latest -> new Result(latest, (int) Math.round(g.score() * 100), found.size() - 1))
                                .orElse(null);
                    })
                    .filter(Objects::nonNull)
                    .toList();
            model.addAttribute("results", results);
        } catch (Exception e) {
            log.warn("Semantic search failed", e);
            model.addAttribute("error", "Search is unavailable right now. Try again in a minute.");
        }
        return "search";
    }
}
