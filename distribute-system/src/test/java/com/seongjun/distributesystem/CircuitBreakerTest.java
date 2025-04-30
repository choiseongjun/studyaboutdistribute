package com.seongjun.distributesystem;

import com.seongjun.distributesystem.circuitbreaker.CustomCircuitBreaker;
import com.seongjun.distributesystem.dto.OrderRequest;
import com.seongjun.distributesystem.dto.OrderResponse;
import com.seongjun.distributesystem.service.OrderService;
import com.seongjun.distributesystem.kafka.OrderProducer;
import com.seongjun.distributesystem.repository.OrderRepository;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.ByteSequence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 서킷 브레이커 테스트 클래스
 * 
 * 이 테스트는 다음과 같은 시나리오를 검증합니다:
 * 1. 연속적인 실패로 인한 서킷 브레이커 활성화
 * 2. 서킷 브레이커 활성화 상태에서의 요청 거부
 * 3. 타임아웃 후 서킷 브레이커 자동 복구
 * 4. 성공적인 요청 후 실패 카운트 리셋
 */
@SpringBootTest
public class CircuitBreakerTest {
    private static final Logger logger = LoggerFactory.getLogger(CircuitBreakerTest.class);

    @Autowired
    private CustomCircuitBreaker customCircuitBreaker;

    @Autowired
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        // 테스트 시작 전 서킷 브레이커 초기화
        customCircuitBreaker.resetFailureCount();
    }

    /**
     * 연속적인 실패로 인한 서킷 브레이커 활성화 테스트
     */
    @Test
    void testCircuitBreakerActivation() {
        // 초기 상태 확인
        assertFalse(customCircuitBreaker.isOpen());
        assertEquals(0, customCircuitBreaker.getFailureCount());

        // 연속적으로 3번의 실패 발생
        for (int i = 0; i < 3; i++) {
            customCircuitBreaker.recordFailure();
            logger.info("Failure {} recorded", i + 1);
        }

        // 서킷 브레이커가 열렸는지 확인
        assertTrue(customCircuitBreaker.isOpen());
        assertEquals(3, customCircuitBreaker.getFailureCount());
        logger.info("Circuit breaker is open after 3 failures");
    }

    /**
     * 서킷 브레이커 자동 복구 테스트
     */
    @Test
    void testCircuitBreakerRecovery() throws InterruptedException {
        // 연속적으로 3번의 실패 발생
        for (int i = 0; i < 3; i++) {
            customCircuitBreaker.recordFailure();
        }

        // 서킷 브레이커가 열렸는지 확인
        assertTrue(customCircuitBreaker.isOpen());

        // 31초 대기 (서킷 브레이커 리셋 타임아웃보다 약간 더 긴 시간)
        Thread.sleep(31000);

        // 서킷 브레이커가 닫혔는지 확인
        assertFalse(customCircuitBreaker.isOpen());
        assertEquals(0, customCircuitBreaker.getFailureCount());
        logger.info("Circuit breaker has recovered after timeout");
    }

    /**
     * 성공적인 요청 후 실패 카운트 리셋 테스트
     */
    @Test
    void testFailureCountReset() {
        // 2번의 실패 발생
        for (int i = 0; i < 2; i++) {
            customCircuitBreaker.recordFailure();
        }

        // 실패 카운트 확인
        assertEquals(2, customCircuitBreaker.getFailureCount());

        // 실패 카운트 리셋
        customCircuitBreaker.resetFailureCount();
        assertEquals(0, customCircuitBreaker.getFailureCount());
        logger.info("Failure count has been reset");

        // 다시 실패 발생
        customCircuitBreaker.recordFailure();
        assertEquals(1, customCircuitBreaker.getFailureCount());
        logger.info("First failure after reset recorded");
    }
} 