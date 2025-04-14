package com.seongjun.distributesystem;

import com.seongjun.distributesystem.dto.OrderRequest;
import com.seongjun.distributesystem.dto.OrderResponse;
import com.seongjun.distributesystem.repository.OrderRepository;
import com.seongjun.distributesystem.service.OrderService;
import com.seongjun.distributesystem.utils.EtcdDistributedLock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 주문 서비스 통합 테스트 클래스
 * 
 * 이 테스트는 다음과 같은 시나리오를 검증합니다:
 * 1. 분산락(etcd)을 사용한 동시성 제어
 * 2. 여러 스레드가 동시에 주문을 처리할 때의 안정성
 * 3. 각 주문이 고유하게 처리되는지 확인
 * 
 * 테스트 프로세스:
 * 1. 20개의 스레드를 생성하여 동시에 주문 처리 시도
 * 2. 각 스레드는 고유한 주문 ID를 사용
 * 3. 분산락으로 인해 동일한 주문은 한 번만 처리됨
 * 4. 모든 주문이 성공적으로 처리되었는지 확인
 */
@SpringBootTest
public class OrderServiceIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(OrderServiceIntegrationTest.class);
    private static final String ETCD_ENDPOINTS = "http://localhost:2379";

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderRepository orderRepository;
    @BeforeEach
    void setUp() {
        // 테스트 시작 전 데이터베이스 초기화
        orderRepository.deleteAll();
    }

    @BeforeAll
    static void checkEtcdConnection() {
        try {

            EtcdDistributedLock lock = new EtcdDistributedLock(ETCD_ENDPOINTS);
            lock.executeWithLock("test-lock", 5, 5, () -> "test");
            lock.destroy();
        } catch (Exception e) {
            logger.error("Failed to connect to etcd server", e);
            throw new RuntimeException("Etcd server is not running or not accessible", e);
        }
    }

    /**
     * 동시성 주문 처리 테스트
     * 
     * 이 테스트는 다음과 같은 내용을 검증합니다:
     * 1. 20개의 스레드가 동시에 주문을 처리할 때의 동작
     * 2. 분산락을 통한 동시성 제어
     * 3. 각 주문의 고유성 보장
     * 
     * 테스트 시나리오:
     * 1. 20개의 스레드를 생성하여 동시에 주문 처리 시도
     * 2. 각 스레드는 고유한 주문 ID를 사용
     * 3. CountDownLatch를 사용하여 모든 스레드가 동시에 시작하도록 제어
     * 4. 모든 주문이 성공적으로 처리되었는지 확인
     * 
     * 검증 내용:
     * 1. 모든 스레드가 성공적으로 처리되었는지 확인
     * 2. 실패한 스레드가 없는지 확인
     * 3. 각 주문이 고유하게 처리되었는지 확인
     */
    @Test
    public void testConcurrentOrderProcessing() throws InterruptedException {
        // 테스트 설정
        final int threadCount = 20;
        final Set<String> results = new HashSet<>();
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(threadCount);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);

        // 스레드 풀 생성
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

        // 각 스레드가 고유한 주문을 처리
        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executorService.submit(() -> {
                try {
                    // 모든 스레드가 동시에 시작하도록 대기
                    startLatch.await();
                    OrderRequest orderRequest = new OrderRequest();
                    orderRequest.setOrderId("test-order-" + threadIndex);  // 각 스레드마다 고유한 orderId
                    // 주문 처리 요청
                    OrderResponse result = orderService.processOrder(orderRequest);

                    // 결과 기록
                    synchronized (results) {
                        results.add(result.getOrderId());
                    }

                    successCount.incrementAndGet();
                    logger.info("Thread {} successfully processed order", threadIndex);
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    logger.error("Thread {} failed to process order: {}", threadIndex, e.getMessage());
                } finally {
                    // 작업 완료 신호
                    endLatch.countDown();
                }
            });
        }

        // 모든 스레드 동시에 시작
        startLatch.countDown();

        // 모든 스레드가 완료될 때까지 대기
        endLatch.await(60, TimeUnit.SECONDS);
        executorService.shutdown();
        executorService.awaitTermination(10, TimeUnit.SECONDS);

        // 결과 확인
        logger.info("All order processing completed");
        logger.info("Unique order processing results: {}", results.size());
        logger.info("Success count: {}", successCount.get());
        logger.info("Failure count: {}", failureCount.get());

        // 모든 스레드가 성공적으로 처리되었는지 확인
        assertEquals(threadCount, successCount.get(), "All threads should succeed");
        assertEquals(0, failureCount.get(), "No threads should fail");

        // 각 처리 결과가 고유한지 확인
        assertEquals(threadCount, results.size(), "Each thread should generate a unique result");
    }
}