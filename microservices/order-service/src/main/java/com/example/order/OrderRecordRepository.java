package com.example.order;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRecordRepository extends JpaRepository<OrderRecordEntity, String> {
    List<OrderRecordEntity> findByUserIdOrderByCreatedAtDesc(String userId);
}
