package com.spendsense.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import com.spendsense.user.AuthUser;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home(@AuthenticationPrincipal AuthUser user) {
        return user == null ? "index" : "redirect:/dashboard";
    }
}
