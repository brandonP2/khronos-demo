package com.moga.khronosdemo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    // The server's notion of "now" for clock-ins, rates and reports. Tests replace it to fix the time.
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
