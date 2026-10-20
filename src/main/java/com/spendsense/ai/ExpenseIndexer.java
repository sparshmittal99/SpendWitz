package com.spendsense.ai;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import com.spendsense.expense.Expense;
import com.spendsense.expense.ExpenseChangedEvent;
import com.spendsense.expense.ExpenseDeletedEvent;

/**
 * Keeps one embedding per expense in pgvector. Used for semantic search ("that coffee place near the office")
 * and for learning categories from the user's past expenses.
 *
 * Indexing runs after the transaction commits and off the request thread, so a slow or failing embedding
 * call never blocks or rolls back saving an expense.
 */
@Service
public class ExpenseIndexer {

    private static final Logger log = LoggerFactory.getLogger(ExpenseIndexer.class);
    private static final int BATCH_SIZE = 10;
    private static final Duration BATCH_PAUSE = Duration.ofSeconds(12);
    private static final Duration RATE_LIMIT_BACKOFF = Duration.ofSeconds(45);
    private static final int MAX_ATTEMPTS = 5;

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;

    public ExpenseIndexer(VectorStore vectorStore, JdbcTemplate jdbc) {
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
    }

    public record Match(long expenseId, String category, String merchant, double score, String text) {
    }

    /** Expenses whose indexed text is identical (e.g. 30 "Uber, Transport" rides), best score first. */
    public record MatchGroup(double score, List<Long> expenseIds) {
    }

    /** Stable id, so re-indexing an edited expense overwrites its old vector. */
    public static String docId(long expenseId) {
        return UUID.nameUUIDFromBytes(("expense:" + expenseId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static Document toDocument(Expense expense) {
        StringBuilder text = new StringBuilder()
                .append(expense.getMerchant())
                .append(". Category: ").append(expense.getCategory().getLabel());
        if (!expense.getItems().isEmpty()) {
            text.append(". Items: ").append(expense.getItems().stream()
                    .map(i -> i.getName()).collect(Collectors.joining(", ")));
        }
        if (expense.getNote() != null && !expense.getNote().isBlank()) {
            text.append(". Note: ").append(expense.getNote());
        }
        Map<String, Object> metadata = Map.of(
                "userId", String.valueOf(expense.getUser().getId()),
                "expenseId", String.valueOf(expense.getId()),
                "category", expense.getCategory().name(),
                "merchant", expense.getMerchant());
        return new Document(docId(expense.getId()), text.toString(), metadata);
    }

    @Async
    @TransactionalEventListener
    public void onExpenseChanged(ExpenseChangedEvent event) {
        try {
            vectorStore.add(List.of(event.document()));
        } catch (Exception e) {
            log.warn("Could not index expense document {}: {}", event.document().getId(), e.getMessage());
        }
    }

    @Async
    @TransactionalEventListener
    public void onExpenseDeleted(ExpenseDeletedEvent event) {
        try {
            vectorStore.delete(List.of(docId(event.expenseId())));
        } catch (Exception e) {
            log.warn("Could not remove expense {} from the index: {}", event.expenseId(), e.getMessage());
        }
    }

    /**
     * Bulk indexing (used for demo data). The free tier limits embedding requests per minute and each document
     * is one request, so this paces itself to ~50 documents a minute and backs off when rate-limited.
     */
    public void indexAll(List<Document> documents) throws InterruptedException {
        for (int i = 0; i < documents.size(); i += BATCH_SIZE) {
            List<Document> batch = new ArrayList<>(documents.subList(i, Math.min(i + BATCH_SIZE, documents.size())));
            for (int attempt = 1; ; attempt++) {
                try {
                    vectorStore.add(batch);
                    break;
                } catch (RuntimeException e) {
                    boolean rateLimited = String.valueOf(e.getMessage()).contains("429");
                    if (!rateLimited || attempt == MAX_ATTEMPTS) {
                        throw e;
                    }
                    log.info("Embedding rate limit hit, retrying batch in {}s", RATE_LIMIT_BACKOFF.toSeconds());
                    Thread.sleep(RATE_LIMIT_BACKOFF);
                }
            }
            Thread.sleep(BATCH_PAUSE);
        }
    }

    public long countForUser(long userId) {
        Long count = jdbc.queryForObject("select count(*) from vector_store where metadata ->> 'userId' = ?",
                Long.class, String.valueOf(userId));
        return count == null ? 0 : count;
    }

    public void removeAllForUser(long userId) {
        vectorStore.delete(new FilterExpressionBuilder().eq("userId", String.valueOf(userId)).build());
    }

    /** Nearest expenses of this user only. The user filter is applied inside the vector query. */
    public List<Match> search(long userId, String query, int topK, double minSimilarity) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(minSimilarity)
                .filterExpression(new FilterExpressionBuilder().eq("userId", String.valueOf(userId)).build())
                .build();
        return vectorStore.similaritySearch(request).stream()
                .map(doc -> new Match(
                        Long.parseLong(String.valueOf(doc.getMetadata().get("expenseId"))),
                        String.valueOf(doc.getMetadata().get("category")),
                        String.valueOf(doc.getMetadata().get("merchant")),
                        doc.getScore() == null ? 0 : doc.getScore(),
                        doc.getText()))
                .toList();
    }

    /**
     * Like {@link #search} but collapses expenses with identical text into one group. Without this, frequent
     * look-alike expenses fill every result slot and hide the distinct ones.
     */
    public List<MatchGroup> searchGrouped(long userId, String query, int maxGroups, double minSimilarity) {
        Map<String, List<Match>> byText = search(userId, query, maxGroups * 8, minSimilarity).stream()
                .collect(Collectors.groupingBy(m -> String.valueOf(m.text()), LinkedHashMap::new, Collectors.toList()));
        return byText.values().stream()
                .map(group -> new MatchGroup(group.getFirst().score(), group.stream().map(Match::expenseId).toList()))
                .limit(maxGroups)
                .toList();
    }
}
