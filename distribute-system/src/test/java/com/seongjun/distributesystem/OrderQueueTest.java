package com.seongjun.distributesystem;

import com.seongjun.distributesystem.controller.OrderController;
import com.seongjun.distributesystem.dto.OrderRequest;
import com.seongjun.distributesystem.dto.OrderResponse;
import com.seongjun.distributesystem.model.Order;
import com.seongjun.distributesystem.repository.OrderRepository;
import com.seongjun.distributesystem.kafka.OrderConsumer;
import com.seongjun.distributesystem.kafka.OrderProducer;
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
 * 1. Kafka를 통한 대량의 주문 처리 (10000개)
 * 2. 고성능 동시성 처리 (CPU 코어 수 기반 최적화)
 * 3. 주문 상태 관리 및 추적
 * 
 * 테스트 프로세스:
 * 1. 10000개의 주문을 생성
 * 2. CPU 코어 수에 기반한 최적의 스레드 수로 동시 처리
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

    @Autowired
    private OrderConsumer orderConsumer;

    @Autowired
    private OrderProducer orderProducer;

    @Autowired
    private KafkaTemplate<String, OrderRequest> kafkaTemplate;

    @BeforeEach
    void setUp() {
        // 테스트 시작 전 데이터베이스 초기화
        orderRepository.deleteAll();
    }

    /**
     * 고성능 동시성 주문 처리 테스트
     * 
     * 이 테스트는 다음과 같은 내용을 검증합니다:
     * 1. 10000개의 주문을 CPU 코어 수에 기반한 최적의 스레드 수로 동시 처리
     * 2. 주문 상태 추적 및 확인
     * 3. 모든 주문이 성공적으로 처리되는지 검증
     * 
     * 테스트 시나리오:
     * 1. 10000개의 고유한 주문 생성
     * 2. 최적화된 스레드 수로 동시 처리 시작
     * 3. 1초 간격으로 주문 상태 확인
     * 4. 최대 60초 동안 모든 주문이 완료될 때까지 대기
     * 
     * 검증 내용:
     * 1. 모든 주문이 COMPLETED 상태로 처리되었는지 확인
     * 2. 주문 처리 시간이 적절한지 확인
     * 3. 주문 처리 중 오류가 발생하지 않았는지 확인
     */
    @Test
    void testHighConcurrencyOrderProcessing() throws Exception {
        // 테스트 설정
        int numberOfOrders = 100000; // 10만개 주문
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int numberOfThreads = Math.max(availableProcessors * 2, 100);
        logger.info("Using {} threads for processing {} orders", numberOfThreads, numberOfOrders);
        
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);

        // 주문 생성 시작 시간
        long startCreateTime = System.currentTimeMillis();
        
        // 주문 생성
        List<OrderRequest> orders = new ArrayList<>();
        for (int i = 0; i < numberOfOrders; i++) {
            orders.add(new OrderRequest(
                UUID.randomUUID().toString(),
                "product-" + (i % 100),
                (i % 10) + 1,
                "user-" + (i % 1000)
            ));
        }
        
        long endCreateTime = System.currentTimeMillis();
        logger.info("Order creation completed in {} ms", (endCreateTime - startCreateTime));

        // 주문 제출 시작 시간
        long startSubmitTime = System.currentTimeMillis();

        // 주문 제출
        List<CompletableFuture<OrderResponse>> futures = orders.stream()
            .map(order -> CompletableFuture.supplyAsync(() -> {
                try {
                    return orderController.createOrder(order).getBody();
                } catch (Exception e) {
                    logger.error("Failed to create order: {}", order.getOrderId(), e);
                    throw new RuntimeException(e);
                }
            }, executorService))
            .collect(Collectors.toList());

        // 모든 주문이 제출될 때까지 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(300, TimeUnit.SECONDS);
        
        long endSubmitTime = System.currentTimeMillis();
        logger.info("Order submission completed in {} ms", (endSubmitTime - startSubmitTime));

        // 주문 처리 시작 시간
        long startProcessTime = System.currentTimeMillis();

        // 주문이 모두 처리될 때까지 대기
        int maxAttempts = 300;  // 최대 300초 대기
        int attempt = 0;
        long completedOrderCount = 0;
        
        while (attempt < maxAttempts) {
            Thread.sleep(1000);
            List<Order> processedOrders = orderRepository.findAll();
            completedOrderCount = processedOrders.stream()
                .filter(order -> "COMPLETED".equals(order.getStatus()))
                .count();
                
            logger.info("Attempt {}: Completed order count: {} ({}%)", 
                attempt + 1, 
                completedOrderCount,
                (completedOrderCount * 100.0 / numberOfOrders));
            
            if (completedOrderCount >= numberOfOrders) {
                break;
            }
            attempt++;
        }
        
        long endProcessTime = System.currentTimeMillis();
        
        // 성능 측정 결과 출력
        logger.info("Performance Summary:");
        logger.info("Total orders processed: {}", completedOrderCount);
        logger.info("Order creation time: {} ms", (endCreateTime - startCreateTime));
        logger.info("Order submission time: {} ms", (endSubmitTime - startSubmitTime));
        logger.info("Order processing time: {} ms", (endProcessTime - startProcessTime));
        logger.info("Total execution time: {} ms", (endProcessTime - startCreateTime));
        logger.info("Average orders per second: {}", 
            (completedOrderCount * 1000.0 / (endProcessTime - startCreateTime)));

        assertEquals(numberOfOrders, completedOrderCount, "모든 주문이 COMPLETED 상태로 처리되어야 합니다");

        executorService.shutdown();
        executorService.awaitTermination(5, TimeUnit.MINUTES);
    }
} 