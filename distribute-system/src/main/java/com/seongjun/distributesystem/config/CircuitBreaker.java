package com.seongjun.distributesystem.config;

public class CircuitBreaker {
    private final int maxFailures;
    private final long resetTimeout;
    private int failureCount = 0;
    private long lastFailureTime = 0;
    private State state = State.CLOSED;

    enum State {
        CLOSED,    // 정상 동작
        OPEN,      // 회로 차단
        HALF_OPEN  // 시험적 동작
    }

    public CircuitBreaker(int maxFailures, long resetTimeout) {
        this.maxFailures = maxFailures;
        this.resetTimeout = resetTimeout;
    }

    public synchronized boolean allowRequest() {
        if (state == State.CLOSED) {
            return true;
        }

        if (state == State.OPEN) {
            // 타임아웃이 지났는지 확인
            if (System.currentTimeMillis() - lastFailureTime >= resetTimeout) {
                state = State.HALF_OPEN;
                return true;
            }
            return false;
        }

        // HALF_OPEN 상태에서는 한 번만 시도
        return true;
    }

    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN) {
            // 성공하면 CLOSED 상태로
            state = State.CLOSED;
            failureCount = 0;
        }
    }

    public synchronized void recordFailure() {
        failureCount++;
        lastFailureTime = System.currentTimeMillis();
        
        if (failureCount >= maxFailures) {
            state = State.OPEN;
            System.out.println("Circuit breaker opened due to " + failureCount + " failures");
        }
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized int getFailureCount() {
        return failureCount;
    }
}