package com.spendsense.dashboard;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.spendsense.common.AiUnavailableException;
import com.spendsense.common.QuotaExceededException;
import com.spendsense.user.AuthUser;

@Controller
public class DashboardController {

    private final DashboardService dashboard;
    private final InsightService insights;

    public DashboardController(DashboardService dashboard, InsightService insights) {
        this.dashboard = dashboard;
        this.insights = insights;
    }

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal AuthUser user, Model model) {
        model.addAttribute("view", dashboard.build(user.id()));
        return "dashboard";
    }

    @PostMapping("/dashboard/insights")
    public String generateInsights(@AuthenticationPrincipal AuthUser user, RedirectAttributes redirect) {
        try {
            insights.generate(user);
        } catch (QuotaExceededException | AiUnavailableException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dashboard";
    }
}
