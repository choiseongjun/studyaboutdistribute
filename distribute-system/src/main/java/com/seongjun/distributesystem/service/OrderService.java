package com.seongjun.distributesystem.service;

import com.seongjun.distributesystem.dto.OrderRequest;
import com.seongjun.distributesystem.dto.OrderResponse;
import com.seongjun.distributesystem.kafka.OrderProducer;
import com.seongjun.distributesystem.model.Order;
import com.seongjun.distributesystem.repository.OrderRepository;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.Lock;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.lease.LeaseGrantResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

@Service
public class OrderService {
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    private static final int MAX_FAILURES = 3;
    private static final long LOCK_TIMEOUT = 5; // 5초
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile boolean circuitBreakerOpen = false;
    
    // 대기열 상태 추적을 위한 변수들
    private final AtomicLong totalOrders = new AtomicLong(0);
    private final AtomicLong processedOrders = new AtomicLong(0);
    private final Map<String, Long> orderQueuePosition = new ConcurrentHashMap<>();

    private final OrderProducer orderProducer;
    private final OrderRepository orderRepository;
    private final Client etcdClient;

    public OrderService(OrderProducer orderProducer, OrderRepository orderRepository, Client etcdClient) {
        this.orderProducer = orderProducer;
        this.orderRepository = orderRepository;
        this.etcdClient = etcdClient;
    }

    public OrderResponse processOrder(OrderRequest orderRequest) {
        if (circuitBreakerOpen) {
            logger.warn("Circuit breaker is open, rejecting request for order: {}", orderRequest.getOrderId());
            return OrderResponse.builder()
                    .orderId(orderRequest.getOrderId())
                    .status("REJECTED")
                    .message("Circuit breaker is open")
                    .build();
        }

        String orderKey = "order-" + orderRequest.getOrderId();
        ByteSequence key = ByteSequence.from(orderKey.getBytes());
        ByteSequence value = ByteSequence.from("PROCESSING".getBytes());

        try {
            // etcd의 KV 클라이언트를 사용하여 원자적 연산 수행
            // 1. 먼저 키가 존재하는지 확인
            var getResponse = etcdClient.getKVClient()
                    .get(key)
                    .get();

            if (!getResponse.getKvs().isEmpty()) {
                logger.warn("Duplicate order ID detected: {}", orderRequest.getOrderId());
                return OrderResponse.builder()
                        .orderId(orderRequest.getOrderId())
                        .status("REJECTED")
                        .message("Duplicate order ID")
                        .build();
            }

            // 2. 키가 존재하지 않으면 새로운 주문 생성
            etcdClient.getKVClient()
                    .put(key, value)
                    .get();

            // 대기열에 주문 추가
            long queuePosition = totalOrders.incrementAndGet();
            orderQueuePosition.put(orderRequest.getOrderId(), queuePosition);
            
            Order order = new Order();
            order.setOrderId(orderRequest.getOrderId());
            order.setProductId(orderRequest.getProductId());
            order.setQuantity(orderRequest.getQuantity());
            order.setUserId(orderRequest.getUserId());
            order.setStatus("PROCESSING");
            order.setSequence(queuePosition);
            orderRepository.save(order);

            orderProducer.sendOrder(orderRequest);

            failureCount.set(0);
            return OrderResponse.builder()
                    .orderId(orderRequest.getOrderId())
                    .status("ACCEPTED")
                    .message("Order processed successfully. Queue position: " + queuePosition)
                    .build();

        } catch (Exception e) {
            logger.error("Failed to process order: {}", orderRequest.getOrderId(), e);
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
        // 현재 주문의 상태 확인
        Order currentOrder = orderRepository.findById(orderId)
            .orElseThrow(() -> new RuntimeException("Order not found"));
        
        // 전체 주문 수와 처리된 주문 수 계산
        long totalOrdersCount = totalOrders.get();
        long processedOrdersCount = processedOrders.get();
        long pendingOrdersCount = totalOrdersCount - processedOrdersCount;
        
        // 현재 주문의 대기열 위치 계산
        Long currentPosition = orderQueuePosition.get(orderId);
        long queuePosition;
        
        if (currentPosition != null) {
            // 주문이 아직 처리 중인 경우
            queuePosition = currentPosition;
        } else if ("COMPLETED".equals(currentOrder.getStatus())) {
            // 주문이 완료된 경우 - sequence 번호 사용
            queuePosition = currentOrder.getSequence();
        } else {
            // 주문이 대기 중인 경우
            queuePosition = pendingOrdersCount + 1;
        }
        
        // 평균 처리 시간이 1초라고 가정
        long estimatedWaitTime = (pendingOrdersCount > 0) ? pendingOrdersCount * 1000 : 0;
        
        Map<String, Object> positionInfo = new HashMap<>();
        positionInfo.put("orderId", orderId);
        positionInfo.put("currentStatus", currentOrder.getStatus());
        positionInfo.put("queuePosition", queuePosition);
        positionInfo.put("pendingOrders", pendingOrdersCount);
        positionInfo.put("processingOrders", orderRepository.countByStatus("PROCESSING"));
        positionInfo.put("completedOrders", processedOrdersCount);
        positionInfo.put("totalOrders", totalOrdersCount);
        positionInfo.put("estimatedWaitTime", estimatedWaitTime);
        
        return positionInfo;
    }

    /**
     * 전체 대기열 상태를 확인합니다.
     * @return 대기열 상태 정보 (각 상태별 주문 수, 총 주문 수)
     */
    public Map<String, Object> getQueueStatus() {
        Map<String, Object> statusInfo = new HashMap<>();
        
        // 전체 주문 수와 처리된 주문 수 계산
        long totalOrdersCount = totalOrders.get();
        long processedOrdersCount = processedOrders.get();
        long pendingOrdersCount = totalOrdersCount - processedOrdersCount;
        
        // 데이터베이스에서 처리 중인 주문 수 확인
        long processingOrdersCount = orderRepository.countByStatus("PROCESSING");
        
        // 대기 중인 주문 수는 전체 주문 수에서 처리된 주문 수를 뺀 값
        // 단, 처리 중인 주문은 pendingOrdersCount에서 제외
        long actualPendingOrdersCount = Math.max(0, pendingOrdersCount - processingOrdersCount);
        
        statusInfo.put("pendingOrders", actualPendingOrdersCount);
        statusInfo.put("processingOrders", processingOrdersCount);
        statusInfo.put("completedOrders", processedOrdersCount);
        statusInfo.put("totalOrders", totalOrdersCount);
        
        // 평균 처리 시간이 1초라고 가정하여 예상 대기 시간 계산
        long estimatedWaitTime = (actualPendingOrdersCount > 0) ? actualPendingOrdersCount * 1000 : 0;
        statusInfo.put("estimatedWaitTime", estimatedWaitTime);
        
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

