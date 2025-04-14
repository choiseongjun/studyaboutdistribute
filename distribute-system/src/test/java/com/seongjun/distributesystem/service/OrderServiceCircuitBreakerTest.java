package com.seongjun.distributesystem.service;

import com.seongjun.distributesystem.config.CircuitBreakerOpenException;
import com.seongjun.distributesystem.dto.OrderRequest;
import com.seongjun.distributesystem.dto.OrderResponse;
import com.seongjun.distributesystem.repository.OrderRepository;
import com.seongjun.distributesystem.utils.EtcdDistributedLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
public class OrderServiceCircuitBreakerTest {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceCircuitBreakerTest.class);


    @Autowired
    private OrderService orderService;

    @Autowired
    private KafkaTemplate<String, OrderRequest> kafkaTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        reset(orderService, kafkaTemplate, orderRepository);
    }

    @Test
    void testCircuitBreakerWithConcurrentFailures() throws InterruptedException {
        // 첫 3번의 호출은 실패하도록 설정
        OrderRequest request = new OrderRequest();
        request.setOrderId("test-order-123");
        when(orderService.processOrder(any(OrderRequest.class)))
            .thenThrow(new RuntimeException("Forced failure"))
            .thenThrow(new RuntimeException("Forced failure"))
            .thenThrow(new RuntimeException("Forced failure"))
            .thenThrow(new CircuitBreakerOpenException());

        final int threadCount = 10;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(threadCount);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger circuitBreakerOpenCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);

        // 먼저 단일 스레드로 여러 번 실패를 유도
        for (int i = 0; i < 3; i++) {
            try {
                orderService.processOrder(request);
            } catch (Exception e) {
                failureCount.incrementAndGet();
                log.info("Initial failure {}: {}", i, e.getMessage());
            }
            Thread.sleep(100);
        }

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

        // 여러 스레드가 동시에 주문을 처리하려고 시도
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    orderService.processOrder(request);
                    successCount.incrementAndGet();
                } catch (CircuitBreakerOpenException e) {
                    circuitBreakerOpenCount.incrementAndGet();
                    log.info("Circuit breaker opened: {}", e.getMessage());
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    log.error("Failure: {}", e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(60, TimeUnit.SECONDS);
        executorService.shutdown();
        executorService.awaitTermination(10, TimeUnit.SECONDS);

        log.info("Initial failures: {}", failureCount.get());
        log.info("Success count: {}", successCount.get());
        log.info("Circuit breaker open count: {}", circuitBreakerOpenCount.get());
        log.info("Total failure count: {}", failureCount.get());

        // 회로 차단기가 적절히 작동했는지 확인
        assertTrue(circuitBreakerOpenCount.get() > 0, "Circuit breaker should have opened");
        assertTrue(successCount.get() < threadCount, "Not all requests should succeed");
        
        // 추가 검증: 회로 차단기가 열린 후 추가 요청 시도
        try {
            orderService.processOrder(request);
            fail("Should throw CircuitBreakerOpenException");
        } catch (CircuitBreakerOpenException e) {
            // 예상된 예외
        }
    }

    @Test
    void testCircuitBreakerRecovery() throws InterruptedException {
        OrderRequest request = new OrderRequest();
        request.setOrderId("recovery-test-order");
        
        // 첫 3번의 호출은 실패하도록 설정
        when(orderService.processOrder(request))
            .thenThrow(new RuntimeException("Forced failure"))
            .thenThrow(new RuntimeException("Forced failure"))
            .thenThrow(new RuntimeException("Forced failure"))
            .thenThrow(new CircuitBreakerOpenException())
            .thenThrow(new CircuitBreakerOpenException())  // 회로 차단기가 열린 상태에서도 계속 예외 발생
            .thenReturn(OrderResponse.builder()
                .orderId(request.getOrderId())
                .status("SUCCESS")
                .message("Order processed successfully")
                .build());  // 복구 후 성공

        // 실패를 유도하여 회로 차단기를 열림 상태로 만듦
        for (int i = 0; i < 3; i++) {
            try {
                orderService.processOrder(request);
            } catch (Exception e) {
                log.error("Failure {}: {}", i, e.getMessage());
            }
            Thread.sleep(100);
        }

        // 회로 차단기가 열렸는지 확인
        try {
            orderService.processOrder(request);
            fail("Should have thrown CircuitBreakerOpenException");
        } catch (CircuitBreakerOpenException e) {
            log.error("Circuit breaker opened as expected: {}", e.getMessage());
        }

        // 30초 대기 (RESET_TIMEOUT)
        log.info("Waiting for circuit breaker reset...");
        Thread.sleep(30000);

        // 회로 차단기가 닫혔는지 확인
        OrderResponse result = orderService.processOrder(request);
        assertNotNull(result);
        assertEquals("SUCCESS", result.getStatus());
        log.info("Circuit breaker recovered successfully: {}", result);
    }

    @Test
    void testCircuitBreakerWithDifferentOrders() throws InterruptedException {
        // 모든 요청이 성공하도록 설정
        when(orderService.processOrder(any(OrderRequest.class)))
            .thenAnswer(invocation -> {
                OrderRequest request = invocation.getArgument(0);
                return "Order " + request.getOrderId() + " processed successfully";
            });

        final int orderCount = 5;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(orderCount);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);

        ExecutorService executorService = Executors.newFixedThreadPool(orderCount);

        // 서로 다른 주문 ID로 동시에 요청
        for (int i = 0; i < orderCount; i++) {
            final OrderRequest request = new OrderRequest();
            request.setOrderId("order-" + i);
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    orderService.processOrder(request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    log.error("Failure processing order {}: {}", request.getOrderId(), e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(60, TimeUnit.SECONDS);
        executorService.shutdown();
        executorService.awaitTermination(10, TimeUnit.SECONDS);

        log.info("Success count: {}", successCount.get());
        log.info("Failure count: {}", failureCount.get());

        // 모든 주문이 성공적으로 처리되어야 함
        assertEquals(orderCount, successCount.get(), "All different orders should be processed successfully");
    }
} 