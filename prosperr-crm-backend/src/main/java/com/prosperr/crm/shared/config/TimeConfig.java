package com.prosperr.crm.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/** A single Clock bean so time-based rules (60-day flag, send window) are testable. */
@Configuration
@EnableScheduling
public class TimeConfig {
    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
