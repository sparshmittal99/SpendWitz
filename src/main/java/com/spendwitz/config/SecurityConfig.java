package com.spendwitz.config;

import java.time.Duration;
import java.util.UUID;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import com.spendwitz.push.PushTokenStore;

@Configuration
public class SecurityConfig {

    /**
     * Signs the Android app's "stay signed in" cookie. A new key on every start: app users stay signed in until they
     * sign out, clear the app's data, or the server restarts.
     */
    private final String rememberMeKey = UUID.randomUUID().toString();

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, PushTokenStore pushTokens) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // streamed assistant responses complete on an async dispatch
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/", "/login", "/register", "/error",
                        "/css/**", "/js/**", "/img/**", "/favicon.svg", "/actuator/health/**").permitAll()
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/dashboard", true)
                .permitAll())
            // Only when the login form sends remember-me=on (the Android app does)
            .rememberMe(remember -> remember
                .key(rememberMeKey)
                .tokenValiditySeconds((int) Duration.ofDays(365).toSeconds()))
            .logout(logout -> logout
                // The Android app sends its phone's token: unlink it, so alerts stop until someone signs in again
                .addLogoutHandler((request, response, authentication) -> {
                    String token = request.getParameter("pushToken");
                    if (token != null && !token.isBlank()) {
                        pushTokens.remove(token.trim());
                    }
                })
                .logoutSuccessUrl("/login?logout")
                .permitAll());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
