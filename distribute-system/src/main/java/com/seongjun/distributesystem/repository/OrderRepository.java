package com.seongjun.distributesystem.repository;

import com.seongjun.distributesystem.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    long countByStatus(String status);
} 