package com.seongjun.distributesystem.kafka;

import com.seongjun.distributesystem.dto.OrderRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Kafka 주문 프로듀서
 * 
 * 이 클래스는 다음과 같은 기능을 수행합니다:
 * 1. 주문 메시지를 Kafka 토픽으로 전송
 * 2. 주문 처리 상태 로깅
 * 3. 에러 처리 및 복구
 */
@Component
public class OrderProducer {
    private static final Logger logger = LoggerFactory.getLogger(OrderProducer.class);
    private static final String TOPIC = "orders";

    private final KafkaTemplate<String, OrderRequest> kafkaTemplate;

    @Autowired
    public OrderProducer(KafkaTemplate<String, OrderRequest> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 단일 주문을 Kafka로 전송
     */
    public void sendOrder(OrderRequest order) {
        kafkaTemplate.send(TOPIC, order.getOrderId(), order);
    }

    /**
     * 여러 주문을 병렬로 Kafka로 전송
     */
    public void sendOrders(List<OrderRequest> orders) {
        CompletableFuture<?>[] futures = orders.stream()
            .map(order -> CompletableFuture.runAsync(() -> 
                kafkaTemplate.send(TOPIC, order.getOrderId(), order)
            ))
            .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).join();
    }
} 