package com.seongjun.distributesystem;

import com.seongjun.distributesystem.service.OrderService;
import com.seongjun.distributesystem.utils.EtcdDistributedLock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest
public class OrderServiceIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(OrderServiceIntegrationTest.class);
    private static final String ETCD_ENDPOINTS = "http://localhost:2379";

    @Autowired
    private OrderService orderService;

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

    @Test
    public void testConcurrentOrderProcessing() throws InterruptedException {
        // 테스트 설정
        final int threadCount = 20;
        final String orderId = "test-order-123";
        final Set<String> results = new HashSet<>();
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(threadCount);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);

        // 스레드 풀 생성
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

        // 각 스레드가 동일한 주문을 처리하려고 시도
        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executorService.submit(() -> {
                try {
                    // 모든 스레드가 동시에 시작하도록 대기
                    startLatch.await();

                    // 주문 처리 요청
                    String result = orderService.processOrder(orderId);

                    // 결과 기록
                    synchronized (results) {
                        results.add(result);
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

        // 각 처리 결과가 고유한지 확인(각 스레드마다 다른 UUID 생성)
        assertEquals(threadCount, results.size(), "Each thread should generate a unique result");
    }

    @Test
    public void testDifferentOrdersParallelProcessing() throws InterruptedException {
        // 서로 다른 주문 ID로 병렬 처리 테스트
        final int orderCount = 10;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(orderCount);
        final Set<String> results = new HashSet<>();

        // 스레드 풀 생성
        ExecutorService executorService = Executors.newFixedThreadPool(orderCount);

        // 서로 다른 주문 ID로 병렬 처리
        for (int i = 0; i < orderCount; i++) {
            final String orderId = "order-" + i;
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    String result = orderService.processOrder(orderId);
                    synchronized (results) {
                        results.add(result);
                    }
                } catch (Exception e) {
                    System.err.println("Error processing order " + orderId + ": " + e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 모든 스레드 동시에 시작
        startLatch.countDown();

        // 모든 처리가 완료될 때까지 대기
        endLatch.await();
        executorService.shutdown();

        // 결과 확인
        System.out.println("All different orders processed");
        System.out.println("Unique results: " + results.size());

        // 각 주문이 고유한 결과를 생성했는지 확인
        assertEquals(orderCount, results.size(), "Each order should generate a unique result");
    }
}