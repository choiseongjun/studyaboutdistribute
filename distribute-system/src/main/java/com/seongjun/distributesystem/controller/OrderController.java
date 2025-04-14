package com.seongjun.distributesystem.controller;

import com.seongjun.distributesystem.dto.OrderRequest;
import com.seongjun.distributesystem.dto.OrderResponse;
import com.seongjun.distributesystem.service.OrderProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderProducer orderProducer;

    @PostMapping
    public OrderResponse createOrder(@RequestBody OrderRequest request) {
        return orderProducer.queueOrder(request);
    }

    @GetMapping("/{orderId}/status")
    public OrderResponse getOrderStatus(@PathVariable String orderId) {
        // TODO: 주문 상태 조회 구현
        return OrderResponse.builder()
                .orderId(orderId)
                .status("UNKNOWN")
                .message("Status check not implemented yet")
                .build();
    }
}