package com.spendwitz.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/account")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public String page(@AuthenticationPrincipal AuthUser user, Model model) {
        model.addAttribute("info", accounts.info(user));
        return "account/index";
    }

    @PostMapping("/delete")
    public String delete(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) String password,
            Authentication authentication, HttpServletRequest request, HttpServletResponse response,
            RedirectAttributes redirect) {
        try {
            accounts.delete(user, password);
        } catch (AccountService.AccountException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/account";
        }
        // The account no longer exists: end the session right away
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        return "redirect:/login?deleted";
    }
}
