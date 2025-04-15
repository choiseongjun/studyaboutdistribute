package com.seongjun.distributesystem.controller;

import com.seongjun.distributesystem.dto.OrderRequest;
import com.seongjun.distributesystem.dto.OrderResponse;
import com.seongjun.distributesystem.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orderService;

    @Autowired
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody OrderRequest request) {
        OrderResponse response = orderService.processOrder(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/batch")
    public List<OrderResponse> createOrders(@RequestBody List<OrderRequest> orderRequests) {
        orderService.sendOrders(orderRequests);
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

    @GetMapping("/{orderId}/position")
    public ResponseEntity<Map<String, Object>> getQueuePosition(@PathVariable String orderId) {
        Map<String, Object> position = orderService.getQueuePosition(orderId);
        return ResponseEntity.ok(position);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getQueueStatus() {
        Map<String, Object> status = orderService.getQueueStatus();
        return ResponseEntity.ok(status);
    }
}