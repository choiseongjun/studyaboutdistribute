package com.seongjun.distributesystem.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {
    
    @Bean
    public Counter orderCounter(MeterRegistry registry) {
        return Counter.builder("orders.total")
                .description("Total number of orders processed")
                .register(registry);
    }
    
    @Bean
    public Timer orderProcessingTimer(MeterRegistry registry) {
        return Timer.builder("orders.processing.time")
                .description("Time taken to process an order")
                .register(registry);
    }
} 