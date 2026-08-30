package com.example.catalog;

import java.io.Serializable;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private static final Logger log = LoggerFactory.getLogger(CatalogController.class);

    private final ProductRepository productRepository;

    public CatalogController(ProductRepository productRepository) {
        this.productRepository = productRepository;
        log.info("Catalog controller wired with PostgreSQL repository");
    }

    @GetMapping("/items")
    @Cacheable(cacheNames = "catalog-items")
    public List<Product> items() {
        List<Product> result = productRepository.findByActiveTrueOrderByIdAsc()
                .stream()
                .map(this::toProduct)
                .sorted(Comparator.comparingInt(Product::id))
                .toList();
        log.info("Catalog items requested, returning {} active product(s)", result.size());
        return result;
    }

    @GetMapping("/items/{id}")
    @Cacheable(cacheNames = "catalog-item", key = "#id")
    public Product byId(@PathVariable("id") int id) {
        log.info("Catalog product requested for id={}", id);
        return productRepository.findById(id)
                .map(this::toProduct)
                .orElseThrow(() -> {
                    log.warn("Catalog product not found for id={}", id);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
                });
    }

    @PostMapping("/admin/items")
    @CacheEvict(cacheNames = { "catalog-items", "catalog-summary" }, allEntries = true)
    public Product create(@RequestBody ProductRequest request) {
        log.info("Admin create product request id={} name={} price={} category={}",
                request.id(), request.name(), request.price(), request.category());
        if (productRepository.existsById(request.id())) {
            log.warn("Create product conflict for id={}", request.id());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Product id already exists");
        }
        ProductEntity entity = new ProductEntity();
        entity.setId(request.id());
        entity.setName(request.name());
        entity.setPrice(request.price());
        entity.setCategory(request.category());
        entity.setActive(request.active());
        ProductEntity saved = productRepository.save(entity);
        Product product = toProduct(saved);
        log.info("Product created id={} totalProducts={}", product.id(), productRepository.count());
        return product;
    }

    @PutMapping("/admin/items/{id}")
    @CacheEvict(cacheNames = { "catalog-items", "catalog-item", "catalog-summary" }, allEntries = true)
    public Product update(@PathVariable("id") int id, @RequestBody ProductRequest request) {
        log.info("Admin update product id={} payloadName={} payloadPrice={} payloadCategory={}",
                id, request.name(), request.price(), request.category());
        if (!productRepository.existsById(id)) {
            log.warn("Update failed, product not found id={}", id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }
        ProductEntity entity = new ProductEntity();
        entity.setId(id);
        entity.setName(request.name());
        entity.setPrice(request.price());
        entity.setCategory(request.category());
        entity.setActive(request.active());
        Product product = toProduct(productRepository.save(entity));
        log.info("Product updated id={}", id);
        return product;
    }

    @GetMapping("/admin/summary")
    @Cacheable(cacheNames = "catalog-summary")
    public Map<String, Object> adminSummary() {
        long total = productRepository.count();
        long activeCount = productRepository.findByActiveTrueOrderByIdAsc().size();
        Map<String, Object> summary = Map.of("totalProducts", total, "activeProducts", activeCount);
        log.info("Catalog admin summary requested: {}", summary);
        return summary;
    }

    private Product toProduct(ProductEntity entity) {
        return new Product(entity.getId(), entity.getName(), entity.getPrice(), entity.getCategory(), entity.isActive());
    }

    public record Product(int id, String name, double price, String category, boolean active) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    public record ProductRequest(int id, String name, double price, String category, boolean active) {
    }
}

