package com.spendwitz.ai;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.spendwitz.common.Category;
import com.spendwitz.expense.ExpenseRepository;

/**
 * Learns categories from the user's own history, so a correction made once sticks:
 * 1. exact merchant match in the database (free, deterministic);
 * 2. otherwise the most similar past expense in pgvector, if it is similar enough.
 * Only if neither applies does the caller fall back to the LLM's guess.
 */
@Service
public class CategorySuggester {

    private static final Logger log = LoggerFactory.getLogger(CategorySuggester.class);
    private static final double MIN_SIMILARITY = 0.82;

    private final ExpenseRepository expenses;
    private final ExpenseIndexer indexer;

    public CategorySuggester(ExpenseRepository expenses, ExpenseIndexer indexer) {
        this.expenses = expenses;
        this.indexer = indexer;
    }

    public record Suggestion(Category category, String reason) {
    }

    /** Cheap, exact lookup only (no embedding call): what did this user file this merchant under last time? */
    public Optional<Suggestion> fromMerchantHistory(long userId, String merchant) {
        if (merchant == null || merchant.isBlank()) {
            return Optional.empty();
        }
        return expenses.findFirstByUserIdAndMerchantIgnoreCaseOrderByExpenseDateDesc(userId, merchant.trim())
                .map(e -> new Suggestion(e.getCategory(),
                        "You filed " + e.getMerchant() + " under this category before."));
    }

    public Optional<Suggestion> fromHistory(long userId, String merchant, List<String> itemNames) {
        if (merchant == null || merchant.isBlank()) {
            return Optional.empty();
        }
        var sameMerchant = expenses.findFirstByUserIdAndMerchantIgnoreCaseOrderByExpenseDateDesc(userId, merchant.trim());
        if (sameMerchant.isPresent()) {
            return Optional.of(new Suggestion(sameMerchant.get().getCategory(),
                    "You filed " + sameMerchant.get().getMerchant() + " under this category before."));
        }
        try {
            String query = itemNames.isEmpty() ? merchant : merchant + ". Items: " + String.join(", ", itemNames);
            return indexer.search(userId, query, 1, MIN_SIMILARITY).stream()
                    .findFirst()
                    .flatMap(match -> Category.parse(match.category())
                            .map(c -> new Suggestion(c, "Similar to your past expense at " + match.merchant() + ".")));
        } catch (Exception e) {
            log.warn("Similarity lookup failed, falling back to AI category: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
