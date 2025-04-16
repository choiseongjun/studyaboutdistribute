package com.seongjun.distributesystem.kafka;

import com.seongjun.distributesystem.dto.OrderRequest;
import com.seongjun.distributesystem.model.Order;
import com.seongjun.distributesystem.repository.OrderRepository;
import com.seongjun.distributesystem.service.OrderService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Kafka 주문 컨슈머
 * 
 * 이 클래스는 다음과 같은 기능을 수행합니다:
 * 1. Kafka 토픽에서 주문 메시지를 수신
 * 2. 주문을 병렬로 처리
 * 3. 처리된 주문을 데이터베이스에 저장
 * 
 * 성능 최적화:
 * 1. 병렬 처리를 위한 스레드 풀 사용
 * 2. 배치 처리로 처리량 향상
 * 3. 컨슈머 그룹 내에서의 병렬 처리
 */
@Component
public class OrderConsumer {
    private static final Logger logger = LoggerFactory.getLogger(OrderConsumer.class);

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final ExecutorService executorService;

    // 병렬 처리를 위한 스레드 풀 크기
    private static final int THREAD_POOL_SIZE = 10;

    @Autowired
    public OrderConsumer(OrderRepository orderRepository, OrderService orderService) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        // 병렬 처리를 위한 스레드 풀 생성
        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }

    /**
     * 주문 메시지를 배치로 처리하는 컨슈머 메소드
     * CompletableFuture를 사용하여 배치 내 주문을 병렬로 처리
     *
     * @param orders 처리할 주문 리스트
     */
    @KafkaListener(
            topics = "orders",
            groupId = "order-group",
            containerFactory = "batchKafkaListenerContainerFactory",
            concurrency = "3" // 컨슈머 인스턴스를 3개 생성하여 병렬 처리
    )
    @Transactional
    public void consumeOrders(List<OrderRequest> orders) {
        if (orders.isEmpty()) {
            return;
        }

        logger.info("Received batch of {} orders", orders.size());

        try {
            // 배치 내 주문을 병렬로 처리
            List<CompletableFuture<Order>> futures = orders.stream()
                    .map(orderRequest -> CompletableFuture.supplyAsync(() -> processOrder(orderRequest), executorService))
                    .collect(Collectors.toList());

            // 모든 비동기 작업이 완료될 때까지 대기
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])
            );

            // 처리 결과 수집
            List<Order> orderEntities = allFutures.thenApply(v ->
                    futures.stream()
                            .map(CompletableFuture::join)
                            .collect(Collectors.toList())
            ).get(60, TimeUnit.SECONDS); // 타임아웃 설정

            // 한 번에 데이터베이스에 저장
            orderRepository.saveAll(orderEntities);

            // 주문 완료 처리
            orderEntities.forEach(order ->
                    orderService.completeOrder(order.getOrderId())
            );

            logger.info("Completed processing batch of {} orders", orders.size());
        } catch (Exception e) {
            logger.error("Error processing batch of orders", e);
            throw new RuntimeException("Failed to process orders batch", e);
        }
    }

    /**
     * 개별 주문을 처리하는 메소드
     *
     * @param orderRequest 처리할 주문 요청
     * @return 처리된 주문 엔티티
     */
    private Order processOrder(OrderRequest orderRequest) {
        try {
            logger.debug("Processing order: {}", orderRequest.getOrderId());

            // 주문 엔티티 생성 및 설정
            Order orderEntity = new Order();
            orderEntity.setOrderId(orderRequest.getOrderId());
            orderEntity.setProductId(orderRequest.getProductId());
            orderEntity.setQuantity(orderRequest.getQuantity());
            orderEntity.setUserId(orderRequest.getUserId());
            orderEntity.setStatus("COMPLETED");

            // 필요한 비즈니스 로직 처리
            // 예: 재고 확인, 결제 검증 등

            logger.debug("Order {} processed successfully", orderRequest.getOrderId());
            return orderEntity;
        } catch (Exception e) {
            logger.error("Failed to process order: {}", orderRequest.getOrderId(), e);
            throw new RuntimeException("Order processing failed for order: " + orderRequest.getOrderId(), e);
        }
    }

    /**
     * Spring 빈 소멸 시 스레드 풀 정리
     */
    @PreDestroy
    public void shutdown() {
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
} 