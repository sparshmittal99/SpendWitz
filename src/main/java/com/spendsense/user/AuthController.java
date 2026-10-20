package com.spendsense.user;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.spendsense.config.AppProperties;

@Controller
public class AuthController {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;

    public AuthController(AppUserRepository users, PasswordEncoder passwordEncoder, AppProperties props) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @GetMapping("/login")
    public String login(@AuthenticationPrincipal AuthUser user, Model model) {
        if (user != null) {
            return "redirect:/dashboard";
        }
        model.addAttribute("demo", props.demo());
        return "login";
    }

    @GetMapping("/register")
    public String registerForm(Model model) {
        model.addAttribute("form", new RegisterForm());
        return "register";
    }

    @PostMapping("/register")
    @Transactional
    public String register(@Valid @ModelAttribute("form") RegisterForm form, BindingResult result,
            RedirectAttributes redirect) {
        if (!result.hasFieldErrors("email") && users.existsByEmailIgnoreCase(form.getEmail().trim())) {
            result.rejectValue("email", "taken", "An account with this email already exists");
        }
        if (result.hasErrors()) {
            return "register";
        }
        users.save(new AppUser(form.getEmail().trim().toLowerCase(), passwordEncoder.encode(form.getPassword()),
                form.getDisplayName().trim(), form.getCurrency(), false));
        redirect.addFlashAttribute("success", "Account created. Log in to start tracking.");
        return "redirect:/login";
    }
}
