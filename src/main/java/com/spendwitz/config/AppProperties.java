package com.spendwitz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spendwitz")
public record AppProperties(String zone, Ai ai, Demo demo) {

    public record Ai(int dailyLimitPerUser, int dailyLimitDemo, int dailyLimitGlobal) {
    }

    public record Demo(boolean enabled, String email, String password) {
    }
}
