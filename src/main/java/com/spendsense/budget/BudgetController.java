package com.spendsense.budget;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.spendsense.common.Category;
import com.spendsense.user.AuthUser;

@Controller
@RequestMapping("/budgets")
public class BudgetController {

    private final BudgetService budgets;
    private final Clock clock;

    public BudgetController(BudgetService budgets, Clock clock) {
        this.budgets = budgets;
        this.clock = clock;
    }

    @GetMapping
    public String page(@AuthenticationPrincipal AuthUser user, Model model) {
        model.addAttribute("limits", budgets.limits(user.id()));
        model.addAttribute("statuses", budgets.statusFor(user.id(), YearMonth.now(clock)));
        return "budgets/index";
    }

    /** Form fields are named after the category code, e.g. FOOD_DINING=6000. */
    @PostMapping
    public String save(@AuthenticationPrincipal AuthUser user, @RequestParam Map<String, String> params,
            RedirectAttributes redirect) {
        Map<Category, BigDecimal> limits = new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            String raw = params.get(category.name());
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                BigDecimal value = new BigDecimal(raw.trim());
                if (value.signum() < 0 || value.precision() - value.scale() > 10) {
                    throw new NumberFormatException();
                }
                limits.put(category, value);
            } catch (NumberFormatException e) {
                redirect.addFlashAttribute("error", "Invalid amount for " + category.getLabel() + ".");
                return "redirect:/budgets";
            }
        }
        budgets.save(user.id(), limits);
        redirect.addFlashAttribute("success", "Budgets saved.");
        return "redirect:/budgets";
    }
}
