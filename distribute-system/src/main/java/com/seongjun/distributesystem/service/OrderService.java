package com.seongjun.distributesystem.service;

import com.seongjun.distributesystem.config.CircuitBreaker;
import com.seongjun.distributesystem.config.CircuitBreakerOpenException;
import com.seongjun.distributesystem.utils.EtcdDistributedLock;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class OrderService {

    private final CircuitBreaker circuitBreaker;
    private final EtcdDistributedLock lock;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 100;
    private static final int MAX_FAILURES = 3;
    private static final long RESET_TIMEOUT = 30000; // 30초

    public OrderService(EtcdDistributedLock lock) {
        this.lock = lock;
        this.circuitBreaker = new CircuitBreaker(MAX_FAILURES, RESET_TIMEOUT);
    }

    public String processOrder(String orderId) {
        // 회로 차단기 상태 확인
        if (!circuitBreaker.allowRequest()) {
            System.out.println("Circuit breaker is open, rejecting request for order: " + orderId);
            throw new CircuitBreakerOpenException();
        }

        // 락 키 생성 (주문ID 기반)
        String lockKey = "order:" + orderId;
        
        // 재시도 로직
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                // 락을 획득하고 비즈니스 로직 실행
                String result = lock.executeWithLock(lockKey, 10, 20, () -> {
                    // 작업 시뮬레이션 (시간 단축)
                    try {
                        Thread.sleep(100); // 100ms로 단축
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "Order " + orderId + " processed by " + UUID.randomUUID();
                });
                
                // 성공 시 회로 차단기 상태 리셋
                circuitBreaker.recordSuccess();
                return result;
            } catch (Exception e) {
                System.out.println("Failed to process order " + orderId + " on attempt " + (attempt + 1) + ": " + e.getMessage());
                // 실패 시 회로 차단기에 기록
                circuitBreaker.recordFailure();
                if (attempt == MAX_RETRIES - 1) {
                    throw e;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MS * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry", ie);
                }
            }
        }
        throw new RuntimeException("Failed to process order after " + MAX_RETRIES + " attempts");
    }
}

