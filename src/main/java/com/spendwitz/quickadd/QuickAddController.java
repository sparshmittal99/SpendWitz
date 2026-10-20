package com.spendwitz.quickadd;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.spendwitz.common.AiUnavailableException;
import com.spendwitz.common.PaymentMethod;
import com.spendwitz.common.QuotaExceededException;
import com.spendwitz.expense.ExpenseForm;
import com.spendwitz.expense.ExpenseService;
import com.spendwitz.expense.ExpenseSource;
import com.spendwitz.user.AuthUser;

/** "Type it or say it": natural-language and voice entry for purchases without bills. */
@Controller
@RequestMapping("/quick-add")
public class QuickAddController {

    private static final int MAX_TEXT = 600;

    private final QuickAddService quickAdd;
    private final ExpenseService expenses;
    private final Clock clock;

    public QuickAddController(QuickAddService quickAdd, ExpenseService expenses, Clock clock) {
        this.quickAdd = quickAdd;
        this.expenses = expenses;
        this.clock = clock;
    }

    @GetMapping
    public String page(@RequestParam(defaultValue = "UPI") PaymentMethod method, Model model) {
        model.addAttribute("method", method);
        model.addAttribute("methods", PaymentMethod.values());
        return "quickadd/index";
    }

    @PostMapping
    public String parse(@AuthenticationPrincipal AuthUser user, @RequestParam String text,
            @RequestParam(defaultValue = "UPI") PaymentMethod method,
            @RequestParam(defaultValue = "false") boolean voice, Model model, RedirectAttributes redirect) {
        if (text.isBlank()) {
            redirect.addFlashAttribute("error", "Type or say what you spent on first.");
            return "redirect:/quick-add?method=" + method;
        }
        if (text.length() > MAX_TEXT) {
            redirect.addFlashAttribute("error", "That's a lot at once. Keep it under " + MAX_TEXT + " characters.");
            return "redirect:/quick-add?method=" + method;
        }
        try {
            var result = quickAdd.parse(user, text.trim(), method);
            if (result.drafts().isEmpty()) {
                redirect.addFlashAttribute("error",
                        "Couldn't find any purchase with an amount in that. Try something like \"sabzi 150, chai 20\".");
                return "redirect:/quick-add?method=" + method;
            }
            QuickAddForm form = new QuickAddForm();
            form.setVoice(voice);
            form.setRows(new ArrayList<>(result.drafts().stream().map(QuickAddForm.Row::from).toList()));
            model.addAttribute("form", form);
            model.addAttribute("unclear", result.unclear());
            model.addAttribute("originalText", text.trim());
            model.addAttribute("methods", PaymentMethod.values());
            return "quickadd/review";
        } catch (QuotaExceededException | AiUnavailableException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/quick-add?method=" + method;
        }
    }

    @PostMapping("/save")
    public String save(@AuthenticationPrincipal AuthUser user, @ModelAttribute("form") QuickAddForm form, Model model,
            RedirectAttributes redirect) {
        List<String> errors = new ArrayList<>();
        List<ExpenseForm> toSave = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);
        for (int i = 0; i < form.getRows().size(); i++) {
            QuickAddForm.Row row = form.getRows().get(i);
            if (!row.isInclude()) {
                continue;
            }
            String label = "Row " + (i + 1);
            if (row.getMerchant() == null || row.getMerchant().isBlank() || row.getMerchant().length() > 150) {
                errors.add(label + ": enter what it was for (up to 150 characters).");
            }
            if (row.getAmount() == null || row.getAmount().signum() <= 0
                    || row.getAmount().compareTo(new BigDecimal("9999999999")) > 0) {
                errors.add(label + ": enter an amount greater than 0.");
            }
            if (row.getCategory() == null || row.getPaymentMethod() == null) {
                errors.add(label + ": choose a category and payment method.");
            }
            if (row.getNote() != null && row.getNote().length() > 500) {
                errors.add(label + ": the note is too long.");
            }
            ExpenseForm expense = new ExpenseForm();
            expense.setMerchant(row.getMerchant());
            expense.setAmount(row.getAmount());
            expense.setCategory(row.getCategory());
            expense.setExpenseDate(row.getExpenseDate() == null ? today : row.getExpenseDate());
            expense.setPaymentMethod(row.getPaymentMethod());
            expense.setNote(row.getNote());
            expense.setSource(row.getSource() == ExpenseSource.RECEIPT || row.getSource() == ExpenseSource.UPI_SCREENSHOT
                    ? row.getSource()
                    : form.isVoice() ? ExpenseSource.VOICE : ExpenseSource.QUICK_ADD);
            toSave.add(expense);
        }
        if (toSave.isEmpty() && errors.isEmpty()) {
            errors.add("Tick at least one expense to save.");
        }
        if (!errors.isEmpty()) {
            model.addAttribute("errors", errors);
            model.addAttribute("methods", PaymentMethod.values());
            return "quickadd/review";
        }
        expenses.createAll(user, toSave);
        redirect.addFlashAttribute("success",
                "Added " + toSave.size() + (toSave.size() == 1 ? " expense." : " expenses."));
        return "redirect:/expenses";
    }
}
