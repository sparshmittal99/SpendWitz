package com.spendwitz.push;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.spendwitz.user.AuthUser;

/** The Android app registers its phone here after sign-in, so over-budget alerts reach it. */
@RestController
public class PushTokenController {

    private static final int MAX_TOKEN_LENGTH = 4096;

    private final PushTokenStore store;

    public PushTokenController(PushTokenStore store) {
        this.store = store;
    }

    @PostMapping("/app/push-token")
    public ResponseEntity<Void> register(@AuthenticationPrincipal AuthUser user, @RequestParam String token) {
        if (token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            return ResponseEntity.badRequest().build();
        }
        store.register(user.id(), token.trim());
        return ResponseEntity.noContent().build();
    }
}
