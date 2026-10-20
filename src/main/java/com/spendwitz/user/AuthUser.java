package com.spendwitz.user;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** The logged-in principal. Controllers take it via {@code @AuthenticationPrincipal AuthUser user}. */
public record AuthUser(long id, String email, String passwordHash, String displayName, String currency, boolean demo)
        implements UserDetails {

    static AuthUser from(AppUser user) {
        return new AuthUser(user.getId(), user.getEmail(), user.getPasswordHash(), user.getDisplayName(),
                user.getCurrency(), user.isDemo());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }
}
