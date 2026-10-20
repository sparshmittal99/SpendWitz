package com.spendsense.cash;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.spendsense.common.Category;
import com.spendsense.common.PaymentMethod;
import com.spendsense.expense.ExpenseForm;
import com.spendsense.expense.ExpenseService;
import com.spendsense.expense.ExpenseSource;
import com.spendsense.user.AuthUser;

/** Cash spending without bills: one-tap entry for chai, sabzi, autos, kirana, plus a cash-in-hand wallet. */
@Controller
@RequestMapping("/cash")
public class CashController {

    private final CashService cash;
    private final ExpenseService expenses;
    private final Clock clock;

    public CashController(CashService cash, ExpenseService expenses, Clock clock) {
        this.cash = cash;
        this.expenses = expenses;
        this.clock = clock;
    }

    public static class CashExpenseForm {

        @NotBlank(message = "What did you pay for?")
        @Size(max = 150)
        private String what;

        @NotNull
        @DecimalMin(value = "0.01", message = "must be greater than 0")
        @Digits(integer = 10, fraction = 2)
        private BigDecimal amount;

        @NotNull(message = "Pick a category")
        private Category category;

        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate date;

        @Size(max = 500)
        private String note;

        public String getWhat() { return what; }
        public void setWhat(String what) { this.what = what; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public Category getCategory() { return category; }
        public void setCategory(Category category) { this.category = category; }
        public LocalDate getDate() { return date; }
        public void setDate(LocalDate date) { this.date = date; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }

    public static class TopUpForm {

        @NotNull
        @DecimalMin(value = "1", message = "must be at least 1")
        @Digits(integer = 10, fraction = 2)
        private BigDecimal amount;

        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate date;

        @Size(max = 200)
        private String note;

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public LocalDate getDate() { return date; }
        public void setDate(LocalDate date) { this.date = date; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }

    @GetMapping
    public String page(@AuthenticationPrincipal AuthUser user, Model model) {
        if (!model.containsAttribute("form")) {
            CashExpenseForm form = new CashExpenseForm();
            form.setDate(LocalDate.now(clock));
            model.addAttribute("form", form);
        }
        if (!model.containsAttribute("topUp")) {
            TopUpForm topUp = new TopUpForm();
            topUp.setDate(LocalDate.now(clock));
            topUp.setNote("ATM withdrawal");
            model.addAttribute("topUp", topUp);
        }
        model.addAttribute("summary", cash.summary(user.id()));
        return "cash/index";
    }

    @PostMapping("/expense")
    public String addExpense(@AuthenticationPrincipal AuthUser user, @Valid @ModelAttribute("form") CashExpenseForm form,
            BindingResult result, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return page(user, model);
        }
        ExpenseForm expense = new ExpenseForm();
        expense.setMerchant(form.getWhat());
        expense.setAmount(form.getAmount());
        expense.setCategory(form.getCategory());
        expense.setExpenseDate(form.getDate());
        expense.setNote(form.getNote());
        expense.setPaymentMethod(PaymentMethod.CASH);
        expense.setSource(ExpenseSource.CASH);
        expenses.create(user, expense);
        redirect.addFlashAttribute("success", "Added cash expense: " + form.getWhat().trim() + ".");
        return "redirect:/cash";
    }

    @PostMapping("/topup")
    public String addTopUp(@AuthenticationPrincipal AuthUser user, @Valid @ModelAttribute("topUp") TopUpForm form,
            BindingResult result, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return page(user, model);
        }
        cash.addTopUp(user.id(), form.getAmount(), form.getDate(), form.getNote());
        redirect.addFlashAttribute("success", "Cash added to your wallet.");
        return "redirect:/cash";
    }

    @PostMapping("/topup/{id}/delete")
    public String deleteTopUp(@AuthenticationPrincipal AuthUser user, @PathVariable long id,
            RedirectAttributes redirect) {
        cash.deleteTopUp(user.id(), id);
        redirect.addFlashAttribute("success", "Entry removed.");
        return "redirect:/cash";
    }
}
