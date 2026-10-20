package com.spendsense.cash;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Cash coming into the wallet: an ATM withdrawal or cash received. */
@Entity
@Table(name = "cash_topup")
public class CashTopUp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CashTopUp() {
    }

    public CashTopUp(Long userId, BigDecimal amount, LocalDate entryDate, String note) {
        this.userId = userId;
        this.amount = amount;
        this.entryDate = entryDate;
        this.note = note;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public BigDecimal getAmount() { return amount; }
    public LocalDate getEntryDate() { return entryDate; }
    public String getNote() { return note; }
}
