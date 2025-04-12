package com.seongjun.distributesystem.controller;

import com.seongjun.distributesystem.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/orders/{orderId}/process")
    public String processOrder(@PathVariable String orderId) {
        return orderService.processOrder(orderId);
    }
}