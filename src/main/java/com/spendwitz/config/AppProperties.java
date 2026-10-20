package com.spendwitz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spendwitz")
public record AppProperties(String zone, Demo demo) {

    public record Demo(boolean enabled, String email, String password) {
    }
}
