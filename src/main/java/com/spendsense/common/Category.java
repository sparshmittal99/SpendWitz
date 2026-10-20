package com.spendsense.common;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/** Fixed spending categories. The code (enum name) is what the LLM sees and returns. */
public enum Category {

    FOOD_DINING("Food & Dining", "bi-cup-hot", "#f97316"),
    GROCERIES("Groceries", "bi-basket", "#22c55e"),
    TRANSPORT("Transport", "bi-car-front", "#3b82f6"),
    SHOPPING("Shopping", "bi-bag", "#ec4899"),
    BILLS_UTILITIES("Bills & Utilities", "bi-lightning-charge", "#eab308"),
    RENT("Rent & Housing", "bi-house", "#8b5cf6"),
    ENTERTAINMENT("Entertainment", "bi-film", "#06b6d4"),
    HEALTH("Health & Fitness", "bi-heart-pulse", "#ef4444"),
    TRAVEL("Travel", "bi-airplane", "#14b8a6"),
    EDUCATION("Education", "bi-book", "#6366f1"),
    OTHER("Other", "bi-three-dots", "#94a3b8");

    private final String label;
    private final String icon;
    private final String color;

    Category(String label, String icon, String color) {
        this.label = label;
        this.icon = icon;
        this.color = color;
    }

    public String getLabel() { return label; }
    public String getIcon() { return icon; }
    public String getColor() { return color; }

    /** Lenient parse for values coming back from the LLM ("Food & Dining", "food_dining", ...). */
    public static Optional<Category> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase().replace('&', ' ').replaceAll("[^A-Z]+", "_").replaceAll("^_|_$", "");
        return Arrays.stream(values())
                .filter(c -> c.name().equals(normalized) || c.label.equalsIgnoreCase(value.trim()))
                .findFirst();
    }

    /** "FOOD_DINING (Food & Dining), GROCERIES (Groceries), ..." for prompts. */
    public static String promptList() {
        return Arrays.stream(values()).map(c -> c.name() + " (" + c.label + ")").collect(Collectors.joining(", "));
    }
}
