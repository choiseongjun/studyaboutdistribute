package com.seongjun.distributesystem.service;

import com.seongjun.distributesystem.dto.OrderRequest;
import com.seongjun.distributesystem.dto.OrderResponse;
import com.seongjun.distributesystem.model.Order;
import com.seongjun.distributesystem.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class OrderProducer {
    private final KafkaTemplate<String, OrderRequest> kafkaTemplate;
    private final OrderRepository orderRepository;
    private static final String ORDER_TOPIC = "orders";

    public OrderProducer(KafkaTemplate<String, OrderRequest> kafkaTemplate, OrderRepository orderRepository) {
        this.kafkaTemplate = kafkaTemplate;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public OrderResponse queueOrder(OrderRequest request) {
        try {
            // 주문 초기 상태 저장
            Order order = new Order();
            order.setOrderId(request.getOrderId());
            order.setProductId(request.getProductId());
            order.setQuantity(request.getQuantity());
            order.setUserId(request.getUserId());
            order.setStatus("QUEUED");
            orderRepository.save(order);

            // Kafka로 주문 전송
            kafkaTemplate.send(ORDER_TOPIC, request.getOrderId(), request);
            log.info("Order queued: {}", request.getOrderId());

            return OrderResponse.builder()
                    .orderId(order.getOrderId())
                    .status(order.getStatus())
                    .message("Order queued successfully")
                    .build();
        } catch (Exception e) {
            log.error("Failed to queue order: {}", request.getOrderId(), e);
            throw new RuntimeException("Failed to queue order: " + e.getMessage(), e);
        }
    }
} 