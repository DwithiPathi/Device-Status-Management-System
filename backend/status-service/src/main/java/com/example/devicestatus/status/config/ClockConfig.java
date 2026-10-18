package com.example.devicestatus.status.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Injected so tests can use a fixed clock for the "not in the future" rule. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
