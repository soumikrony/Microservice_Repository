package com.example.order;

import java.util.Map;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.server.ResponseStatusException;

@Component
public class OrderWorkflowClient {
    private static final Logger log = LoggerFactory.getLogger(OrderWorkflowClient.class);
    private final InventoryHttpClient inventoryClient;
    private final PaymentHttpClient paymentClient;
    private final CartHttpClient cartClient;

    public OrderWorkflowClient(InventoryHttpClient inventoryClient, PaymentHttpClient paymentClient,
            CartHttpClient cartClient) {
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
        this.cartClient = cartClient;
    }

    @Retry(name = "inventoryReserve", fallbackMethod = "reserveFallback")
    @CircuitBreaker(name = "inventoryReserve", fallbackMethod = "reserveFallback")
    @Bulkhead(name = "inventoryReserve", type = Bulkhead.Type.SEMAPHORE)
    public Map<String, Object> reserveItem(int productId, int quantity) {
        return inventoryClient.reserve(Map.of("productId", productId, "quantity", quantity));
    }

    @Retry(name = "paymentCharge", fallbackMethod = "paymentFallback")
    @CircuitBreaker(name = "paymentCharge", fallbackMethod = "paymentFallback")
    @Bulkhead(name = "paymentCharge", type = Bulkhead.Type.SEMAPHORE)
    public Map<String, Object> chargePayment(String orderId, String userId, double amount, String paymentMethod) {
        return paymentClient.charge(Map.of("orderId", orderId, "userId", userId,
                "amount", amount, "paymentMethod", paymentMethod));
    }

    public void releaseItem(int productId, int quantity, String reason) {
        inventoryClient.release(Map.of("productId", productId, "quantity", quantity, "reason", reason));
    }

    public void clearCart(String userId) { cartClient.clear(userId); }
    public Map<String, Object> getCart(String userId) { return cartClient.get(userId); }

    private Map<String, Object> reserveFallback(int productId, int quantity, Throwable ex) {
        log.error("Inventory reservation fallback triggered productId={} quantity={}", productId, quantity, ex);
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Inventory reservation temporarily unavailable: " + ex.getMessage());
    }

    private Map<String, Object> paymentFallback(String orderId, String userId, double amount,
            String paymentMethod, Throwable ex) {
        log.error("Payment fallback triggered orderId={} userId={} amount={}", orderId, userId, amount, ex);
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Payment service temporarily unavailable: " + ex.getMessage());
    }
}

@HttpExchange(accept = "application/json")
interface InventoryHttpClient {
    @PostExchange("/api/inventory/reserve")
    Map<String, Object> reserve(@RequestBody Map<String, Object> request);
    @PostExchange("/api/inventory/release")
    Map<String, Object> release(@RequestBody Map<String, Object> request);
}

@HttpExchange(accept = "application/json")
interface PaymentHttpClient {
    @PostExchange("/api/payments/charge")
    Map<String, Object> charge(@RequestBody Map<String, Object> request);
}

@HttpExchange(accept = "application/json")
interface CartHttpClient {
    @DeleteExchange("/api/cart/{userId}/clear")
    void clear(@PathVariable String userId);
    @GetExchange("/api/cart/{userId}")
    Map<String, Object> get(@PathVariable String userId);
}
