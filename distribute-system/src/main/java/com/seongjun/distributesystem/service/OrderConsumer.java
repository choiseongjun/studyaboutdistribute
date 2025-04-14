package com.seongjun.distributesystem.service;

import com.seongjun.distributesystem.dto.OrderRequest;
import com.seongjun.distributesystem.model.Order;
import com.seongjun.distributesystem.repository.OrderRepository;
import com.seongjun.distributesystem.utils.EtcdDistributedLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class OrderConsumer {
    private final OrderRepository orderRepository;
    private final EtcdDistributedLock distributedLock;
    private static final int LOCK_TTL = 30; // 30 seconds
    private static final int LOCK_RETRY = 3;

    public OrderConsumer(OrderRepository orderRepository, 
                        @Value("${etcd.endpoints}") String etcdEndpoints) {
        this.orderRepository = orderRepository;
        this.distributedLock = new EtcdDistributedLock(etcdEndpoints);
    }

    @KafkaListener(topics = "orders", groupId = "order-service-group")
    @Transactional
    public void processOrder(OrderRequest request) {
        log.info("Received order request: {}", request.getOrderId());
        
        try {
            // 주문 상태를 PROCESSING으로 업데이트
            Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new RuntimeException("Order not found: " + request.getOrderId()));
            order.setStatus("PROCESSING");
            orderRepository.save(order);

            // 분산락을 사용하여 주문 처리
            String lockKey = "order:" + request.getOrderId();
            distributedLock.executeWithLock(lockKey, LOCK_TTL, LOCK_RETRY, () -> {
                try {
                    // 재고 확인 및 업데이트 (여기서는 단순화)
                    // 실제로는 재고 서비스와 통신하여 재고 확인 및 업데이트
                    
                    // 주문 처리 완료
                    order.setStatus("COMPLETED");
                    orderRepository.save(order);
                    
                    log.info("Order processed successfully: {}", request.getOrderId());
                    return null;
                } catch (Exception e) {
                    log.error("Failed to process order: {}", request.getOrderId(), e);
                    order.setStatus("FAILED");
                    orderRepository.save(order);
                    throw e;
                }
            });
        } catch (Exception e) {
            log.error("Failed to process order: {}", request.getOrderId(), e);
            // 실패 시 상태 업데이트
            Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new RuntimeException("Order not found: " + request.getOrderId()));
            order.setStatus("FAILED");
            orderRepository.save(order);
        }
    }
} 