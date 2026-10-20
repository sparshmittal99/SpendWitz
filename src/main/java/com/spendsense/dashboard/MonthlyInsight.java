package com.spendsense.dashboard;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Cached AI insights for one user and month, so the dashboard doesn't call the model on every load. */
@Entity
@Table(name = "monthly_insight")
public class MonthlyInsight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String month;

    @Column(nullable = false)
    private String content;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    protected MonthlyInsight() {
    }

    public MonthlyInsight(Long userId, String month) {
        this.userId = userId;
        this.month = month;
    }

    public void replace(List<String> insights) {
        this.content = String.join("\n", insights);
        this.generatedAt = Instant.now();
    }

    public List<String> getInsights() {
        return Arrays.stream(content.split("\n")).filter(s -> !s.isBlank()).toList();
    }

    public Instant getGeneratedAt() { return generatedAt; }
    public String getMonth() { return month; }
}
