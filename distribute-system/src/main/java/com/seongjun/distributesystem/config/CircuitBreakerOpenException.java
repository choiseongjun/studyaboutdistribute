package com.seongjun.distributesystem.config;

public class CircuitBreakerOpenException extends RuntimeException {
    public CircuitBreakerOpenException() {
        super("Circuit breaker is open.try again later.");
    }
} 