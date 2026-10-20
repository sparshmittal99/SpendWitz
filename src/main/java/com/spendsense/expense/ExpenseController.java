package com.spendsense.expense;

import java.time.Clock;
import java.time.LocalDate;

import jakarta.validation.Valid;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.spendsense.common.Category;
import com.spendsense.common.PaymentMethod;
import com.spendsense.user.AuthUser;

@Controller
@RequestMapping("/expenses")
public class ExpenseController {

    private final ExpenseService service;
    private final Clock clock;

    public ExpenseController(ExpenseService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal AuthUser user,
            @RequestParam(required = false) Category category,
            @RequestParam(required = false) PaymentMethod method,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("page", service.list(user, category, method, q, from, to, page));
        model.addAttribute("category", category);
        model.addAttribute("method", method);
        model.addAttribute("q", q);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        return "expenses/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        ExpenseForm form = new ExpenseForm();
        form.setExpenseDate(LocalDate.now(clock));
        model.addAttribute("form", form);
        return "expenses/form";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal AuthUser user, @Valid @ModelAttribute("form") ExpenseForm form,
            BindingResult result, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return "expenses/form";
        }
        Expense expense = service.create(user, form);
        redirect.addFlashAttribute("success", "Saved " + expense.getMerchant() + ".");
        return "redirect:/expenses";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@AuthenticationPrincipal AuthUser user, @PathVariable long id, Model model) {
        model.addAttribute("form", service.formFor(user, id));
        model.addAttribute("expenseId", id);
        return "expenses/form";
    }

    @PostMapping("/{id}")
    public String update(@AuthenticationPrincipal AuthUser user, @PathVariable long id,
            @Valid @ModelAttribute("form") ExpenseForm form, BindingResult result, Model model,
            RedirectAttributes redirect) {
        if (result.hasErrors()) {
            model.addAttribute("expenseId", id);
            return "expenses/form";
        }
        service.update(user, id, form);
        redirect.addFlashAttribute("success", "Expense updated.");
        return "redirect:/expenses";
    }

    @PostMapping("/{id}/delete")
    public String delete(@AuthenticationPrincipal AuthUser user, @PathVariable long id, RedirectAttributes redirect) {
        service.delete(user, id);
        redirect.addFlashAttribute("success", "Expense deleted.");
        return "redirect:/expenses";
    }
}
