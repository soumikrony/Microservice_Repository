package com.example.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CartControllerTest {

    private CartItemRepository repository;
    private CartController controller;

    @BeforeEach
    void setUp() {
        repository = mock(CartItemRepository.class);
        controller = new CartController(repository);
    }

    @Test
    void getCartCalculatesTotalFromDatabaseItems() {
        CartItemEntity item = new CartItemEntity();
        item.setProductId(7);
        item.setName("Keyboard");
        item.setQuantity(2);
        item.setPrice(25);
        when(repository.findByUserId("alice")).thenReturn(List.of(item));

        var cart = controller.getCart("alice");

        assertThat(cart.get("userId")).isEqualTo("alice");
        assertThat(cart.get("total")).isEqualTo(50.0);
    }

    @Test
    void addItemRejectsNonPositiveQuantity() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                controller.addItem("alice", new CartController.AddCartItemRequest(7, "Keyboard", 0, 25)));

        assertThat(error.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(repository);
    }

    @Test
    void clearDeletesAllItemsForUser() {
        var result = controller.clear("alice");

        assertThat(result).containsEntry("status", "CLEARED").containsEntry("userId", "alice");
        verify(repository).deleteByUserId("alice");
    }
}
