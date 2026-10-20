package com.spendsense.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.spendsense.common.Category;
import com.spendsense.common.Money;
import com.spendsense.common.PaymentMethod;
import com.spendsense.user.AuthUser;

/** Values every page template can use. */
@ControllerAdvice(annotations = org.springframework.stereotype.Controller.class)
public class GlobalModelAttributes {

    @ModelAttribute("currentUser")
    public AuthUser currentUser(@AuthenticationPrincipal AuthUser user) {
        return user;
    }

    @ModelAttribute("currencySymbol")
    public String currencySymbol(@AuthenticationPrincipal AuthUser user) {
        return Money.symbol(user == null ? "INR" : user.currency());
    }

    @ModelAttribute("categories")
    public Category[] categories() {
        return Category.values();
    }

    @ModelAttribute("paymentMethods")
    public PaymentMethod[] paymentMethods() {
        return PaymentMethod.values();
    }
}
