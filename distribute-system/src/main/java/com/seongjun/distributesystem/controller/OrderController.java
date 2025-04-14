package com.seongjun.distributesystem.controller;

import com.seongjun.distributesystem.dto.OrderRequest;
import com.seongjun.distributesystem.dto.OrderResponse;
import com.seongjun.distributesystem.kafka.OrderProducer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderProducer orderProducer;

    @Autowired
    public OrderController(OrderProducer orderProducer) {
        this.orderProducer = orderProducer;
    }

    @PostMapping
    public OrderResponse createOrder(@RequestBody OrderRequest orderRequest) {
        orderProducer.sendOrder(orderRequest);
        return OrderResponse.builder()
            .orderId(orderRequest.getOrderId())
            .status("PROCESSING")
            .message("Order received and being processed")
            .build();
    }

    @PostMapping("/batch")
    public List<OrderResponse> createOrders(@RequestBody List<OrderRequest> orderRequests) {
        orderProducer.sendOrders(orderRequests);
        return orderRequests.stream()
            .map(order -> OrderResponse.builder()
                .orderId(order.getOrderId())
                .status("PROCESSING")
                .message("Order received and being processed")
                .build())
            .toList();
    }

    @GetMapping("/{orderId}/status")
    public OrderResponse getOrderStatus(@PathVariable String orderId) {
        // Implement order status check logic
        return OrderResponse.builder()
            .orderId(orderId)
            .status("UNKNOWN")
            .message("Status check not implemented")
            .build();
    }
}