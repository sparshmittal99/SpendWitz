package com.spendwitz.quickadd;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;

import com.spendwitz.common.Category;
import com.spendwitz.common.PaymentMethod;
import com.spendwitz.expense.ExpenseForm;
import com.spendwitz.expense.ExpenseSource;

/** The review table: one editable row per parsed expense, each with an "include" tick box. */
public class QuickAddForm {

    private boolean voice;

    private List<Row> rows = new ArrayList<>();

    public static class Row {

        private boolean include = true;
        private String merchant;
        private BigDecimal amount;
        private Category category;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate expenseDate;

        private PaymentMethod paymentMethod;
        private String note;
        /** Set for rows read from a bill or UPI screenshot; otherwise the source comes from {@link #voice}. */
        private ExpenseSource source;
        /** The bill's number or UPI transaction id, kept hidden for spotting the same bill again. */
        private String reference;
        /** Set when this bill was already saved or is in the upload twice; the row then starts unticked. */
        private String duplicateOf;

        public Row() {
        }

        /** A row for one scanned bill, so several bills can be reviewed and saved together. */
        public static Row fromScan(ExpenseForm scanned, String duplicateOf) {
            Row row = new Row();
            row.reference = scanned.getReference();
            row.duplicateOf = duplicateOf;
            row.include = duplicateOf == null;
            row.merchant = scanned.getMerchant();
            row.amount = scanned.getAmount();
            row.category = scanned.getCategory();
            row.expenseDate = scanned.getExpenseDate();
            row.paymentMethod = scanned.getPaymentMethod();
            row.note = scanned.getNote();
            row.source = scanned.getSource();
            return row;
        }

        static Row from(QuickAddService.Draft draft) {
            Row row = new Row();
            row.merchant = draft.merchant();
            row.amount = draft.amount();
            row.category = draft.category();
            row.expenseDate = draft.expenseDate();
            row.paymentMethod = draft.paymentMethod();
            row.note = draft.note();
            return row;
        }

        public boolean isInclude() { return include; }
        public void setInclude(boolean include) { this.include = include; }
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
        public ExpenseSource getSource() { return source; }
        public void setSource(ExpenseSource source) { this.source = source; }
        public String getReference() { return reference; }
        public void setReference(String reference) { this.reference = reference; }
        public String getDuplicateOf() { return duplicateOf; }
        public void setDuplicateOf(String duplicateOf) { this.duplicateOf = duplicateOf; }
    }

    public boolean isVoice() { return voice; }
    public void setVoice(boolean voice) { this.voice = voice; }
    public List<Row> getRows() { return rows; }
    public void setRows(List<Row> rows) { this.rows = rows; }
}
