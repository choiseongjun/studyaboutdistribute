package com.seongjun.distributesystem.service;

import com.seongjun.distributesystem.utils.EtcdDistributedLock;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class OrderService {

    private final EtcdDistributedLock lock;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 100;

    public OrderService(EtcdDistributedLock lock) {
        this.lock = lock;
    }

    public String processOrder(String orderId) {
        // 락 키 생성 (주문ID 기반)
        String lockKey = "order:" + orderId;
        
        // 재시도 로직
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                // 락을 획득하고 비즈니스 로직 실행
                return lock.executeWithLock(lockKey, 10, 20, () -> {
                    // 작업 시뮬레이션 (시간 단축)
                    try {
                        Thread.sleep(100); // 100ms로 단축
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "Order " + orderId + " processed by " + UUID.randomUUID();
                });
            } catch (Exception e) {
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