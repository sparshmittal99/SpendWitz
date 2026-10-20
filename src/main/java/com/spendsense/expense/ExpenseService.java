package com.spendsense.expense;

import java.time.LocalDate;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spendsense.ai.ExpenseIndexer;
import com.spendsense.common.Category;
import com.spendsense.common.PaymentMethod;
import com.spendsense.user.AppUserRepository;
import com.spendsense.user.AuthUser;

@Service
public class ExpenseService {

    private static final int PAGE_SIZE = 20;

    private final ExpenseRepository expenses;
    private final AppUserRepository users;
    private final ApplicationEventPublisher events;

    public ExpenseService(ExpenseRepository expenses, AppUserRepository users, ApplicationEventPublisher events) {
        this.expenses = expenses;
        this.users = users;
        this.events = events;
    }

    @Transactional
    public Expense create(AuthUser user, ExpenseForm form) {
        Expense expense = new Expense(users.getReferenceById(user.id()), form.getSource());
        apply(expense, form);
        expenses.save(expense);
        events.publishEvent(new ExpenseChangedEvent(ExpenseIndexer.toDocument(expense)));
        return expense;
    }

    /** Saves several expenses at once (quick add / voice), all or nothing. */
    @Transactional
    public List<Expense> createAll(AuthUser user, List<ExpenseForm> forms) {
        return forms.stream().map(form -> create(user, form)).toList();
    }

    @Transactional
    public Expense update(AuthUser user, long id, ExpenseForm form) {
        Expense expense = get(user, id);
        apply(expense, form);
        events.publishEvent(new ExpenseChangedEvent(ExpenseIndexer.toDocument(expense)));
        return expense;
    }

    @Transactional
    public void delete(AuthUser user, long id) {
        expenses.delete(get(user, id));
        events.publishEvent(new ExpenseDeletedEvent(id));
    }

    @Transactional(readOnly = true)
    public ExpenseForm formFor(AuthUser user, long id) {
        return ExpenseForm.from(expenses.findWithItemsByIdAndUserId(id, user.id())
                .orElseThrow(() -> new ExpenseNotFoundException(id)));
    }

    @Transactional(readOnly = true)
    public Page<Expense> list(AuthUser user, Category category, PaymentMethod paymentMethod, String query,
            LocalDate from, LocalDate to, int page) {
        var pageable = PageRequest.of(Math.max(page, 0), PAGE_SIZE,
                Sort.by(Sort.Order.desc("expenseDate"), Sort.Order.desc("id")));
        return expenses.findAll(ExpenseSpecs.filter(user.id(), category, paymentMethod, query, from, to), pageable);
    }

    private Expense get(AuthUser user, long id) {
        return expenses.findByIdAndUserId(id, user.id()).orElseThrow(() -> new ExpenseNotFoundException(id));
    }

    private static void apply(Expense expense, ExpenseForm form) {
        String note = form.getNote() == null || form.getNote().isBlank() ? null : form.getNote().trim();
        expense.update(form.getMerchant().trim(), form.getAmount(), form.getCategory(), form.getExpenseDate(), note);
        expense.setPaymentMethod(form.getPaymentMethod());
        expense.replaceItems(form.toItems());
    }
}
