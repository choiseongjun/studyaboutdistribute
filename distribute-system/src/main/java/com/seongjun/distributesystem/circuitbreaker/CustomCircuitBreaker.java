package com.seongjun.distributesystem.circuitbreaker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 서킷 브레이커 구현 클래스
 * 
 * 이 클래스는 다음과 같은 기능을 제공합니다:
 * 1. 연속적인 실패 횟수 추적
 * 2. 서킷 브레이커 상태 관리 (열림/닫힘)
 * 3. 자동 복구 메커니즘
 */
@Component
public class CustomCircuitBreaker {
    private static final Logger logger = LoggerFactory.getLogger(CustomCircuitBreaker.class);
    private static final int MAX_FAILURES = 3;
    private static final long RESET_TIMEOUT = 30; // 30초

    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile boolean circuitBreakerOpen = false;
    private volatile long lastFailureTime = 0;

    /**
     * 서킷 브레이커가 열려있는지 확인
     * @return 서킷 브레이커가 열려있으면 true, 아니면 false
     */
    public boolean isOpen() {
        if (circuitBreakerOpen) {
            // 서킷 브레이커가 열려있고, 리셋 타임아웃이 지났다면 서킷을 닫음
            if (System.currentTimeMillis() - lastFailureTime > RESET_TIMEOUT * 1000) {
                circuitBreakerOpen = false;
                failureCount.set(0);
                logger.info("Circuit breaker has been reset after timeout");
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * 실패 기록
     */
    public void recordFailure() {
        int failures = failureCount.incrementAndGet();
        lastFailureTime = System.currentTimeMillis();
        
        if (failures >= MAX_FAILURES) {
            circuitBreakerOpen = true;
            logger.warn("Circuit breaker opened after {} failures", failures);
        }
    }

    /**
     * 실패 카운트 리셋
     */
    public void resetFailureCount() {
        failureCount.set(0);
        logger.info("Failure count has been reset");
    }

    /**
     * 현재 실패 횟수 조회
     * @return 현재 실패 횟수
     */
    public int getFailureCount() {
        return failureCount.get();
    }

    /**
     * 서킷 브레이커 상태 조회
     * @return 서킷 브레이커가 열려있으면 true, 아니면 false
     */
    public boolean isCircuitBreakerOpen() {
        return circuitBreakerOpen;
    }

    /**
     * 마지막 실패 시간 조회
     * @return 마지막 실패 시간 (밀리초)
     */
    public long getLastFailureTime() {
        return lastFailureTime;
    }
} 