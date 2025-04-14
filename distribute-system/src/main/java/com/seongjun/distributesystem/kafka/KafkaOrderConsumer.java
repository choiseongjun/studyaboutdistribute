package com.seongjun.distributesystem.kafka;

import com.seongjun.distributesystem.dto.OrderRequest;
import com.seongjun.distributesystem.model.Order;
import com.seongjun.distributesystem.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class KafkaOrderConsumer {
    private static final Logger logger = LoggerFactory.getLogger(KafkaOrderConsumer.class);

    @Autowired
    private OrderRepository orderRepository;

    @KafkaListener(topics = "orders", groupId = "order-group")
    public void consumeOrder(OrderRequest orderRequest) {
        try {
            logger.info("Consuming order: {}", orderRequest.getOrderId());
            
            // 중복 주문 체크
            Optional<Order> existingOrder = orderRepository.findById(orderRequest.getOrderId());
            if (existingOrder.isPresent()) {
                logger.warn("Duplicate order detected: {}", orderRequest.getOrderId());
                return;
            }
            
            Order order = new Order();
            order.setOrderId(orderRequest.getOrderId());
            order.setProductId(orderRequest.getProductId());
            order.setQuantity(orderRequest.getQuantity());
            order.setUserId(orderRequest.getUserId());
            order.setStatus("PROCESSING");
            
            orderRepository.save(order);
            logger.info("Order saved successfully: {}", orderRequest.getOrderId());
        } catch (Exception e) {
            logger.error("Failed to process order: {}", orderRequest.getOrderId(), e);
        }
    }
} 