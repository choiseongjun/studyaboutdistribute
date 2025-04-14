package com.seongjun.distributesystem.kafka;

import com.seongjun.distributesystem.dto.OrderRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class KafkaOrderProducer {
    private static final Logger logger = LoggerFactory.getLogger(KafkaOrderProducer.class);
    private static final String TOPIC = "orders";

    @Autowired
    private KafkaTemplate<String, OrderRequest> kafkaTemplate;

    public CompletableFuture<SendResult<String, OrderRequest>> sendOrder(OrderRequest orderRequest) {
        logger.info("Sending order to Kafka: {}", orderRequest.getOrderId());
        
        // 메시지 키로 orderId를 사용하여 동일한 주문이 같은 파티션으로 가도록 함
        return kafkaTemplate.send(TOPIC, orderRequest.getOrderId(), orderRequest)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        logger.info("Order sent successfully: {}", orderRequest.getOrderId());
                    } else {
                        logger.error("Failed to send order: {}", orderRequest.getOrderId(), ex);
                    }
                });
    }
} 