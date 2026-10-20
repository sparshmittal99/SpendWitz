package com.spendsense.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;

import com.spendsense.common.Category;
import com.spendsense.common.PaymentMethod;

/** The review table for an imported statement: one editable row per outgoing transaction. */
public class StatementImportForm {

    public static final String EXPENSE = "EXPENSE";
    public static final String ATM_WITHDRAWAL = "ATM_WITHDRAWAL";

    private List<Row> rows = new ArrayList<>();

    public static class Row {

        private boolean include = true;
        /** EXPENSE, or ATM_WITHDRAWAL (goes to the cash wallet instead of being counted as spending). */
        private String kind = EXPENSE;
        private String merchant;
        private BigDecimal amount;
        private Category category;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate expenseDate;

        private PaymentMethod paymentMethod;
        /** The original bank narration, kept as the note so re-imports can be recognised. */
        private String note;
        /** Set when this transaction already exists in SpendSense. */
        private String duplicateOf;
        /** Set when the amount couldn't be confirmed against the statement text. */
        private boolean needsCheck;

        public boolean isAtmWithdrawal() {
            return ATM_WITHDRAWAL.equals(kind);
        }

        public boolean isInclude() { return include; }
        public void setInclude(boolean include) { this.include = include; }
        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        public String getMerchant() { return merchant; }
        public void setMerchant(String merchant) { this.merchant = merchant; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public Category getCategory() { return category; }
        public void setCategory(Category category) { this.category = category; }
        public LocalDate getExpenseDate() { return expenseDate; }
        public void setExpenseDate(LocalDate expenseDate) { this.expenseDate = expenseDate; }
        public PaymentMethod getPaymentMethod() { return paymentMethod; }
        public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
        public String getDuplicateOf() { return duplicateOf; }
        public void setDuplicateOf(String duplicateOf) { this.duplicateOf = duplicateOf; }
        public boolean isNeedsCheck() { return needsCheck; }
        public void setNeedsCheck(boolean needsCheck) { this.needsCheck = needsCheck; }
    }

    public List<Row> getRows() { return rows; }
    public void setRows(List<Row> rows) { this.rows = rows; }
}
