package com.spendwitz.expense;

import org.springframework.ai.document.Document;

/** Published when an expense is created or edited; carries the snapshot to embed. */
public record ExpenseChangedEvent(Document document) {
}
