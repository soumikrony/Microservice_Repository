package com.example.cart;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    private static final Logger log = LoggerFactory.getLogger(CartController.class);

    private final CartItemRepository cartItemRepository;

    public CartController(CartItemRepository cartItemRepository) {
        this.cartItemRepository = cartItemRepository;
    }

    @GetMapping("/{userId}")
    @Cacheable(cacheNames = "cart-by-user", key = "#userId")
    public Map<String, Object> getCart(@PathVariable("userId") String userId) {
        List<CartItem> items = cartItemRepository.findByUserId(userId)
                .stream()
                .map(e -> new CartItem(e.getProductId(), e.getName(), e.getQuantity(), e.getPrice()))
                .toList();
        double total = items.stream().mapToDouble(item -> item.price() * item.quantity()).sum();
        log.info("Cart requested userId={} itemCount={} total={}", userId, items.size(), total);
        return Map.of("userId", userId, "items", items, "total", total);
    }

    @PostMapping("/{userId}/items")
    @CacheEvict(cacheNames = "cart-by-user", key = "#userId")
    public Map<String, Object> addItem(@PathVariable("userId") String userId, @RequestBody AddCartItemRequest request) {
        log.info("Add cart item userId={} productId={} quantity={} price={}",
                userId, request.productId(), request.quantity(), request.price());
        if (request.quantity() <= 0 || request.price() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cart request");
        }

        CartItemEntity existing = cartItemRepository.findByUserIdAndProductId(userId, request.productId()).orElse(null);
        if (existing == null) {
            CartItemEntity entity = new CartItemEntity();
            entity.setUserId(userId);
            entity.setProductId(request.productId());
            entity.setName(request.name());
            entity.setQuantity(request.quantity());
            entity.setPrice(request.price());
            cartItemRepository.save(entity);
        } else {
            existing.setQuantity(existing.getQuantity() + request.quantity());
            cartItemRepository.save(existing);
        }

        return getCart(userId);
    }

    @PutMapping("/{userId}/items/{productId}")
    @CacheEvict(cacheNames = "cart-by-user", key = "#userId")
    public Map<String, Object> updateItem(
            @PathVariable("userId") String userId,
            @PathVariable("productId") int productId,
            @RequestBody UpdateQuantityRequest request) {

        log.info("Update cart item userId={} productId={} newQuantity={}", userId, productId, request.quantity());
        CartItemEntity existing = cartItemRepository.findByUserIdAndProductId(userId, productId).orElse(null);
        if (existing == null) {
            log.warn("Update failed, cart item not found userId={} productId={}", userId, productId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not present in cart");
        }
        if (request.quantity() > 0) {
            existing.setQuantity(request.quantity());
            cartItemRepository.save(existing);
        } else {
            cartItemRepository.delete(existing);
        }

        return getCart(userId);
    }

    @DeleteMapping("/{userId}/items/{productId}")
    @CacheEvict(cacheNames = "cart-by-user", key = "#userId")
    public Map<String, Object> removeItem(@PathVariable("userId") String userId, @PathVariable("productId") int productId) {
        log.info("Remove cart item userId={} productId={}", userId, productId);
        cartItemRepository.findByUserIdAndProductId(userId, productId).ifPresent(cartItemRepository::delete);
        return getCart(userId);
    }

    @DeleteMapping("/{userId}/clear")
    @CacheEvict(cacheNames = "cart-by-user", key = "#userId")
    public Map<String, Object> clear(@PathVariable("userId") String userId) {
        log.info("Clear cart requested userId={}", userId);
        cartItemRepository.deleteByUserId(userId);
        return new LinkedHashMap<>(Map.of("status", "CLEARED", "userId", userId));
    }

    public record AddCartItemRequest(int productId, String name, int quantity, double price) {
    }

    public record UpdateQuantityRequest(int quantity) {
    }

    public record CartItem(int productId, String name, int quantity, double price) implements Serializable {
        private static final long serialVersionUID = 1L;
    }
}

