package com.seongjun.distributesystem.service;

import com.seongjun.distributesystem.dto.OrderRequest;
import com.seongjun.distributesystem.dto.OrderResponse;
import com.seongjun.distributesystem.kafka.OrderProducer;
import com.seongjun.distributesystem.model.Order;
import com.seongjun.distributesystem.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.HashMap;
import java.util.ArrayList;

@Service
public class OrderService {
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    private static final int MAX_FAILURES = 3;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile boolean circuitBreakerOpen = false;
    
    // 대기열 상태 추적을 위한 변수들
    private final AtomicLong totalOrders = new AtomicLong(0);
    private final AtomicLong processedOrders = new AtomicLong(0);
    private final Map<String, Long> orderQueuePosition = new ConcurrentHashMap<>();

    @Autowired
    private OrderProducer orderProducer;

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
            // 대기열에 주문 추가
            long queuePosition = totalOrders.incrementAndGet();
            orderQueuePosition.put(orderRequest.getOrderId(), queuePosition);
            
            Order order = new Order();
            order.setOrderId(orderRequest.getOrderId());
            order.setProductId(orderRequest.getProductId());
            order.setQuantity(orderRequest.getQuantity());
            order.setUserId(orderRequest.getUserId());
            order.setStatus("PROCESSING");
            orderRepository.save(order);

            orderProducer.sendOrder(orderRequest);

            failureCount.set(0);
            return OrderResponse.builder()
                    .orderId(orderRequest.getOrderId())
                    .status("ACCEPTED")
                    .message("Order processed successfully. Queue position: " + queuePosition)
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

    // 주문 처리 완료 시 호출되는 메서드
    public void completeOrder(String orderId) {
        processedOrders.incrementAndGet();
        orderQueuePosition.remove(orderId);
        
        // 현재 대기열 상태 로깅
        long currentProcessed = processedOrders.get();
        long currentTotal = totalOrders.get();
        long remainingOrders = currentTotal - currentProcessed;
        
        logger.info("Order {} completed. Progress: {}/{} ({}%)", 
            orderId, 
            currentProcessed, 
            currentTotal,
            String.format("%.2f", (currentProcessed * 100.0 / currentTotal)));
    }

    /**
     * 주문의 대기열 위치를 확인합니다.
     * @param orderId 주문 ID
     * @return 대기열 위치 정보 (대기 중인 주문 수, 예상 대기 시간)
     */
    public Map<String, Object> getQueuePosition(String orderId) {
        long pendingCount = orderRepository.countByStatus("PENDING");
        long processingCount = orderRepository.countByStatus("PROCESSING");
        
        // 평균 처리 시간이 1초라고 가정
        long estimatedWaitTime = (pendingCount + processingCount) * 1000;
        
        Map<String, Object> positionInfo = new HashMap<>();
        positionInfo.put("orderId", orderId);
        positionInfo.put("pendingOrders", pendingCount);
        positionInfo.put("processingOrders", processingCount);
        positionInfo.put("estimatedWaitTime", estimatedWaitTime);
        
        return positionInfo;
    }

    /**
     * 전체 대기열 상태를 확인합니다.
     * @return 대기열 상태 정보 (각 상태별 주문 수, 총 주문 수)
     */
    public Map<String, Object> getQueueStatus() {
        Map<String, Object> statusInfo = new HashMap<>();
        
        long pendingCount = orderRepository.countByStatus("PENDING");
        long processingCount = orderRepository.countByStatus("PROCESSING");
        long completedCount = orderRepository.countByStatus("COMPLETED");
        
        statusInfo.put("pendingOrders", pendingCount);
        statusInfo.put("processingOrders", processingCount);
        statusInfo.put("completedOrders", completedCount);
        statusInfo.put("totalOrders", pendingCount + processingCount + completedCount);
        
        return statusInfo;
    }

    /**
     * 여러 주문을 일괄 처리합니다.
     * @param orderRequests 주문 요청 목록
     * @return 처리 결과 목록
     */
    public List<OrderResponse> sendOrders(List<OrderRequest> orderRequests) {
        List<OrderResponse> responses = new ArrayList<>();
        
        for (OrderRequest request : orderRequests) {
            try {
                OrderResponse response = processOrder(request);
                responses.add(response);
            } catch (Exception e) {
                logger.error("Failed to process order: " + request.getOrderId(), e);
                responses.add(OrderResponse.builder()
                    .orderId(request.getOrderId())
                    .status("FAILED")
                    .message("Failed to process order: " + e.getMessage())
                    .build());
            }
        }
        
        return responses;
    }
}

