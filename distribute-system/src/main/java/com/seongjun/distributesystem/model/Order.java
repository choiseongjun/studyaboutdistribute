package com.seongjun.distributesystem.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import lombok.Data;

@Data
@Entity
@Table(name = "orders")
public class Order {
    @Id
    private String orderId;
    private String productId;
    private Integer quantity;
    private String userId;
    private String status;
    
    @Column(nullable = true)
    private Long sequence;
} 