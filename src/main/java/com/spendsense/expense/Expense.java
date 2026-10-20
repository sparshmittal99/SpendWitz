package com.spendsense.expense;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import com.spendsense.common.Category;
import com.spendsense.common.PaymentMethod;
import com.spendsense.user.AppUser;

@Entity
@Table(name = "expense")
public class Expense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(nullable = false)
    private String merchant;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExpenseSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private PaymentMethod paymentMethod = PaymentMethod.UPI;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "expense", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<ExpenseItem> items = new ArrayList<>();

    protected Expense() {
    }

    public Expense(AppUser user, ExpenseSource source) {
        this.user = user;
        this.source = source;
    }

    public void update(String merchant, BigDecimal amount, Category category, LocalDate expenseDate, String note) {
        this.merchant = merchant;
        this.amount = amount;
        this.category = category;
        this.expenseDate = expenseDate;
        this.note = note;
    }

    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public void replaceItems(List<ExpenseItem> newItems) {
        items.clear();
        for (int i = 0; i < newItems.size(); i++) {
            ExpenseItem item = newItems.get(i);
            item.attach(this, i);
            items.add(item);
        }
    }

    public Long getId() { return id; }
    public AppUser getUser() { return user; }
    public String getMerchant() { return merchant; }
    public BigDecimal getAmount() { return amount; }
    public Category getCategory() { return category; }
    public LocalDate getExpenseDate() { return expenseDate; }
    public String getNote() { return note; }
    public ExpenseSource getSource() { return source; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public Instant getCreatedAt() { return createdAt; }
    public List<ExpenseItem> getItems() { return items; }
}
