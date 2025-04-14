package com.seongjun.distributesystem;

import com.seongjun.distributesystem.controller.OrderController;
import com.seongjun.distributesystem.dto.OrderRequest;
import com.seongjun.distributesystem.dto.OrderResponse;
import com.seongjun.distributesystem.model.Order;
import com.seongjun.distributesystem.repository.OrderRepository;
import com.seongjun.distributesystem.service.OrderConsumer;
import com.seongjun.distributesystem.service.OrderProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kafka 기반 주문 큐 테스트 클래스
 * 
 * 이 테스트는 다음과 같은 시나리오를 검증합니다:
 * 1. Kafka를 통한 대량의 주문 처리
 * 2. 고성능 동시성 처리
 * 3. 주문 상태 관리 및 추적
 * 
 * 테스트 프로세스:
 * 1. 1000개의 주문을 생성
 * 2. 50개의 스레드로 동시 처리
 * 3. 주문 상태를 주기적으로 확인
 * 4. 모든 주문이 완료될 때까지 대기
 */
@SpringBootTest
public class OrderQueueTest {
    private static final Logger logger = LoggerFactory.getLogger(OrderQueueTest.class);

    @Autowired
    private OrderController orderController;

    @Autowired
    private OrderRepository orderRepository;

//    @Autowired
//    private OrderProducer orderProducer;
//
//    @Autowired
//    private OrderConsumer orderConsumer;
//
//    @Autowired
//    private KafkaTemplate<String, OrderRequest> kafkaTemplate;

    @BeforeEach
    void setUp() {
        // 테스트 시작 전 데이터베이스 초기화
        orderRepository.deleteAll();
    }

    /**
     * 고성능 동시성 주문 처리 테스트
     * 
     * 이 테스트는 다음과 같은 내용을 검증합니다:
     * 1. 1000개의 주문을 50개의 스레드로 동시 처리
     * 2. 주문 상태 추적 및 확인
     * 3. 모든 주문이 성공적으로 처리되는지 검증
     * 
     * 테스트 시나리오:
     * 1. 1000개의 고유한 주문 생성
     * 2. 50개의 스레드로 동시 처리 시작
     * 3. 1초 간격으로 주문 상태 확인
     * 4. 최대 30초 동안 모든 주문이 완료될 때까지 대기
     * 
     * 검증 내용:
     * 1. 모든 주문이 COMPLETED 상태로 처리되었는지 확인
     * 2. 주문 처리 시간이 적절한지 확인
     * 3. 주문 처리 중 오류가 발생하지 않았는지 확인
     */
    @Test
    void testHighConcurrencyOrderProcessing() throws Exception {
        // 테스트 설정
        int numberOfOrders = 1000; // 생성할 주문 수
        int numberOfThreads = 50;  // 동시에 실행할 스레드 수
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);

        // 주문 생성
        List<OrderRequest> orders = new ArrayList<>();
        for (int i = 0; i < numberOfOrders; i++) {
            orders.add(new OrderRequest(
                UUID.randomUUID().toString(),
                "product-" + (i % 10),  // 10개의 상품 중 하나
                (i % 5) + 1,           // 1-5 사이의 수량
                "user-" + (i % 100)    // 100명의 사용자 중 하나
            ));
        }

        // 주문 제출
        List<CompletableFuture<OrderResponse>> futures = orders.stream()
            .map(order -> CompletableFuture.supplyAsync(() -> {
                try {
                    return orderController.createOrder(order);
                } catch (Exception e) {
                    logger.error("Failed to create order: {}", order.getOrderId(), e);
                    throw new RuntimeException(e);
                }
            }, executorService))
            .collect(Collectors.toList());

        // 모든 주문이 제출될 때까지 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);

        // 주문이 모두 처리될 때까지 대기
        int maxAttempts = 30;  // 최대 30초 대기
        int attempt = 0;
        long completedOrderCount = 0;
        
        while (attempt < maxAttempts) {
            Thread.sleep(1000); // 1초 대기
            List<Order> processedOrders = orderRepository.findAll();
            completedOrderCount = processedOrders.stream()
                .filter(order -> "COMPLETED".equals(order.getStatus()))
                .count();
                
            logger.info("Attempt {}: Completed order count: {}", attempt + 1, completedOrderCount);
            
            if (completedOrderCount >= numberOfOrders) {
                break;
            }
            attempt++;
        }
        
        assertEquals(numberOfOrders, completedOrderCount, "모든 주문이 COMPLETED 상태로 처리되어야 합니다");

        executorService.shutdown();
        executorService.awaitTermination(1, TimeUnit.MINUTES);
    }
} 