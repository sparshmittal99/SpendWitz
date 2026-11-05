package com.spendwitz.web;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestHeader;

import com.spendwitz.common.Category;
import com.spendwitz.common.Money;
import com.spendwitz.common.PaymentMethod;
import com.spendwitz.user.AuthUser;

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

    /** Marker the Android app adds to its WebView user agent. */
    public static final String APP_USER_AGENT = "SpendWitzAndroid";

    /** True when the page is shown inside the Android app, which gets an app-style shell (bottom bar, settings). */
    @ModelAttribute("inApp")
    public boolean inApp(@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return userAgent != null && userAgent.contains(APP_USER_AGENT);
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
