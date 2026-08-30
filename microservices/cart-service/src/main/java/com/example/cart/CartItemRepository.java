package com.example.cart;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface CartItemRepository extends JpaRepository<CartItemEntity, Long> {
    List<CartItemEntity> findByUserId(String userId);
    Optional<CartItemEntity> findByUserIdAndProductId(String userId, int productId);
    @Modifying
    @Transactional
    void deleteByUserId(String userId);
}
