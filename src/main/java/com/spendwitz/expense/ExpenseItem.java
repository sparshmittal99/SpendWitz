package com.spendwitz.expense;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "expense_item")
public class ExpenseItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "expense_id")
    private Expense expense;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String name;

    private BigDecimal quantity;

    private BigDecimal price;

    protected ExpenseItem() {
    }

    public ExpenseItem(String name, BigDecimal quantity, BigDecimal price) {
        this.name = name;
        this.quantity = quantity;
        this.price = price;
    }

    void attach(Expense expense, int position) {
        this.expense = expense;
        this.position = position;
    }

    public Long getId() { return id; }
    public int getPosition() { return position; }
    public String getName() { return name; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getPrice() { return price; }
}
