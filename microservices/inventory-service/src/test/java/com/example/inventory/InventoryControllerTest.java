package com.example.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class InventoryControllerTest {

    private InventoryItemRepository repository;
    private InventoryController controller;

    @BeforeEach
    void setUp() {
        repository = mock(InventoryItemRepository.class);
        controller = new InventoryController(repository, new SimpleMeterRegistry());
    }

    @Test
    void reserveReducesAvailableStock() {
        InventoryItemEntity item = item(7, 10);
        when(repository.findById(7)).thenReturn(Optional.of(item));
        when(repository.save(item)).thenReturn(item);

        var result = controller.reserve(new InventoryController.ReserveRequest(7, 3));

        assertThat(result).containsEntry("status", "RESERVED").containsEntry("remaining", 7);
        assertThat(item.getAvailable()).isEqualTo(7);
        verify(repository).save(item);
    }

    @Test
    void reserveRejectsInsufficientStock() {
        InventoryItemEntity item = item(7, 2);
        when(repository.findById(7)).thenReturn(Optional.of(item));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.reserve(new InventoryController.ReserveRequest(7, 3)));

        assertThat(error.getStatusCode().value()).isEqualTo(409);
        verify(repository, never()).save(any());
    }

    @Test
    void releaseAddsStock() {
        InventoryItemEntity item = item(7, 2);
        when(repository.findById(7)).thenReturn(Optional.of(item));
        when(repository.save(item)).thenReturn(item);

        var result = controller.release(new InventoryController.ReleaseRequest(7, 4, "PAYMENT_FAILED"));

        assertThat(result).containsEntry("status", "RELEASED").containsEntry("available", 6);
    }

    private InventoryItemEntity item(int productId, int available) {
        InventoryItemEntity item = new InventoryItemEntity();
        item.setProductId(productId);
        item.setAvailable(available);
        return item;
    }
}
