package com.spendsense.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    /** All "today" / "this month" logic uses the app's zone, not the server's (servers usually run in UTC). */
    @Bean
    Clock clock(AppProperties props) {
        return Clock.system(ZoneId.of(props.zone()));
    }
}
