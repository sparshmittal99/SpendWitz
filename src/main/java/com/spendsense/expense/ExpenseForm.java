package com.spendsense.expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;

import com.spendsense.common.Category;
import com.spendsense.common.PaymentMethod;

/** Backs both the manual entry form and the "review scanned receipt" form. */
public class ExpenseForm {

    @NotBlank
    @Size(max = 150)
    private String merchant;

    @NotNull
    @DecimalMin(value = "0.01", message = "must be greater than 0")
    @Digits(integer = 10, fraction = 2)
    private BigDecimal amount;

    @NotNull
    private Category category;

    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate expenseDate;

    @Size(max = 500)
    private String note;

    private ExpenseSource source = ExpenseSource.MANUAL;

    @NotNull
    private PaymentMethod paymentMethod = PaymentMethod.UPI;

    @Valid
    @Size(max = 50)
    private List<ItemForm> items = new ArrayList<>();

    public static class ItemForm {

        @Size(max = 200)
        private String name;

        @Digits(integer = 8, fraction = 2)
        private BigDecimal quantity;

        @Digits(integer = 10, fraction = 2)
        private BigDecimal price;

        public ItemForm() {
        }

        public ItemForm(String name, BigDecimal quantity, BigDecimal price) {
            this.name = name;
            this.quantity = quantity;
            this.price = price;
        }

        boolean isBlank() {
            return name == null || name.isBlank();
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public BigDecimal getQuantity() { return quantity; }
        public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
    }

    public static ExpenseForm from(Expense expense) {
        ExpenseForm form = new ExpenseForm();
        form.merchant = expense.getMerchant();
        form.amount = expense.getAmount();
        form.category = expense.getCategory();
        form.expenseDate = expense.getExpenseDate();
        form.note = expense.getNote();
        form.source = expense.getSource();
        form.paymentMethod = expense.getPaymentMethod();
        form.items = new ArrayList<>(expense.getItems().stream()
                .map(i -> new ItemForm(i.getName(), i.getQuantity(), i.getPrice())).toList());
        return form;
    }

    List<ExpenseItem> toItems() {
        return items.stream()
                .filter(i -> !i.isBlank())
                .map(i -> new ExpenseItem(i.getName().trim(), i.getQuantity(), i.getPrice()))
                .toList();
    }

    public String getMerchant() { return merchant; }
    public void setMerchant(String merchant) { this.merchant = merchant; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public LocalDate getExpenseDate() { return expenseDate; }
    public void setExpenseDate(LocalDate expenseDate) { this.expenseDate = expenseDate; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public ExpenseSource getSource() { return source; }
    public void setSource(ExpenseSource source) { this.source = source; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }
    public List<ItemForm> getItems() { return items; }
    public void setItems(List<ItemForm> items) { this.items = items; }
}
