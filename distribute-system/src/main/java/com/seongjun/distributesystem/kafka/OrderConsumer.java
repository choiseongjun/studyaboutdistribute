package com.seongjun.distributesystem.kafka;

import com.seongjun.distributesystem.dto.OrderRequest;
import com.seongjun.distributesystem.model.Order;
import com.seongjun.distributesystem.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Kafka 주문 컨슈머
 * 
 * 이 클래스는 다음과 같은 기능을 수행합니다:
 * 1. Kafka 토픽에서 주문 메시지를 수신
 * 2. 주문을 병렬로 처리
 * 3. 처리된 주문을 데이터베이스에 저장
 * 
 * 성능 최적화:
 * 1. 병렬 처리를 위한 스레드 풀 사용
 * 2. 배치 처리로 처리량 향상
 * 3. 컨슈머 그룹 내에서의 병렬 처리
 */
@Component
public class OrderConsumer {
    private static final Logger logger = LoggerFactory.getLogger(OrderConsumer.class);
    private final OrderRepository orderRepository;

    @Autowired
    public OrderConsumer(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * 주문 메시지를 배치로 처리하는 컨슈머 메소드
     * 
     * @param orders 처리할 주문 리스트
     */
    @KafkaListener(
        topics = "orders",
        groupId = "order-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consumeOrders(List<OrderRequest> orders) {
        if (orders.isEmpty()) {
            return;
        }

        List<Order> orderEntities = new ArrayList<>(orders.size());
        for (OrderRequest order : orders) {
            Order orderEntity = new Order();
            orderEntity.setOrderId(order.getOrderId());
            orderEntity.setProductId(order.getProductId());
            orderEntity.setQuantity(order.getQuantity());
            orderEntity.setUserId(order.getUserId());
            orderEntity.setStatus("COMPLETED");
            orderEntities.add(orderEntity);
        }
            
        orderRepository.saveAll(orderEntities);
        logger.info("Completed processing batch of {} orders", orders.size());
    }
} 