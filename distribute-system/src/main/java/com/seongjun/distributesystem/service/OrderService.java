package com.seongjun.distributesystem.service;

import com.seongjun.distributesystem.dto.OrderRequest;
import com.seongjun.distributesystem.dto.OrderResponse;
import com.seongjun.distributesystem.kafka.KafkaOrderProducer;
import com.seongjun.distributesystem.model.Order;
import com.seongjun.distributesystem.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class OrderService {
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    private static final int MAX_FAILURES = 3;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile boolean circuitBreakerOpen = false;

    @Autowired
    private KafkaOrderProducer orderProducer;

    @Autowired
    private OrderRepository orderRepository;

    public OrderResponse processOrder(OrderRequest orderRequest) {
        if (circuitBreakerOpen) {
            logger.warn("Circuit breaker is open, rejecting request for order: {}", orderRequest.getOrderId());
            return OrderResponse.builder()
                    .orderId(orderRequest.getOrderId())
                    .status("REJECTED")
                    .message("Circuit breaker is open")
                    .build();
        }

        try {
            CompletableFuture<Void> future = orderProducer.sendOrder(orderRequest)
                    .thenAccept(result -> {
                        Order order = new Order();
                        order.setOrderId(orderRequest.getOrderId());
                        order.setProductId(orderRequest.getProductId());
                        order.setQuantity(orderRequest.getQuantity());
                        order.setUserId(orderRequest.getUserId());
                        order.setStatus("PROCESSING");
                        orderRepository.save(order);
                    });

            future.get(); // Wait for the operation to complete

            failureCount.set(0); // Reset failure count on success
            return OrderResponse.builder()
                    .orderId(orderRequest.getOrderId())
                    .status("ACCEPTED")
                    .message("Order processed successfully")
                    .build();

        } catch (Exception e) {
            int failures = failureCount.incrementAndGet();
            logger.error("Failed to process order: {}, failure count: {}", orderRequest.getOrderId(), failures, e);

            if (failures >= MAX_FAILURES) {
                circuitBreakerOpen = true;
                logger.warn("Circuit breaker opened after {} failures", failures);
            }

            return OrderResponse.builder()
                    .orderId(orderRequest.getOrderId())
                    .status("FAILED")
                    .message("Failed to process order: " + e.getMessage())
                    .build();
        }
    }
}

