package com.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CatalogControllerTest {

    private ProductRepository repository;
    private CatalogController controller;

    @BeforeEach
    void setUp() {
        repository = mock(ProductRepository.class);
        controller = new CatalogController(repository);
    }

    @Test
    void itemsReturnsOnlyActiveProductsInIdOrder() {
        ProductEntity second = product(2, "Second", true);
        ProductEntity first = product(1, "First", true);
        when(repository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(second, first));

        List<CatalogController.Product> result = controller.items();

        assertThat(result).extracting(CatalogController.Product::id).containsExactly(1, 2);
    }

    @Test
    void byIdThrowsNotFoundWhenProductDoesNotExist() {
        when(repository.findById(99)).thenReturn(java.util.Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.byId(99));

        assertThat(error.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void createRejectsDuplicateProductId() {
        when(repository.existsById(10)).thenReturn(true);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.create(new CatalogController.ProductRequest(10, "Duplicate", 5, "BOOK", true)));

        assertThat(error.getStatusCode().value()).isEqualTo(409);
        verify(repository, never()).save(any());
    }

    private ProductEntity product(int id, String name, boolean active) {
        ProductEntity entity = new ProductEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setPrice(10);
        entity.setCategory("BOOK");
        entity.setActive(active);
        return entity;
    }
}
