package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Product;
import com.loai.inventory.domain.repository.ProductRepository;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Owns business rules and transaction boundaries for Product.
 *
 * Dependency imports:
 *   ✓ domain    (Product, ProductRepository interface)
 *   ✓ common    (exceptions)
 *   ✓ jOOQ      (DSLContext — only for .transaction(), never for SQL)
 *   ✗ repository impl  (ProductRepositoryImpl never imported here)
 *   ✗ api / DTOs       (CreateProductRequest etc. never imported here)
 */
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository repo;
    private final DSLContext        dsl;

    public ProductService(ProductRepository repo, DSLContext dsl) {
        this.repo = repo;
        this.dsl  = dsl;
    }

    // ── Queries

    public Product getById(UUID id) {
        return repo.findById(id)
                   .orElseThrow(() -> new NotFoundException("Product", id));
    }

    public List<Product> getAll(int page, int size) {
        if (page < 0) throw new ValidationException("page must be >= 0");
        if (size < 1 || size > 100) throw new ValidationException("size must be 1-100");
        return repo.findAll(page * size, size);
    }

    public long count() {
        return repo.count();
    }

    // ── Commands

    public Product create(String name, String description, BigDecimal basePrice, String sku) {
        validateProductFields(name, basePrice, sku);

        // Transactional: the SKU uniqueness check + insert must be atomic
        return dsl.transactionResult(config -> {
            if (repo.existsBySku(sku)) {
                throw new ConflictException("SKU already exists: " + sku);
            }

            Product product = new Product();
            product.setName(name);
            product.setDescription(description);
            product.setBasePrice(basePrice);
            product.setSku(sku);
            // id, createdAt, updatedAt are set by the DB (DEFAULT in migration)

            Product saved = repo.insert(product);
            log.info("Created product id={} sku={}", saved.getId(), saved.getSku());
            return saved;
        });
    }

    public Product update(UUID id, String name, String description,
                          BigDecimal basePrice, String sku) {
        validateProductFields(name, basePrice, sku);

        return dsl.transactionResult(config -> {
            // Ensure product exists first
            Product existing = repo.findById(id)
                .orElseThrow(() -> new NotFoundException("Product", id));

            // SKU uniqueness: another product must not already use this SKU
            if (repo.existsBySkuAndIdNot(sku, id)) {
                throw new ConflictException("SKU already used by another product: " + sku);
            }

            existing.setName(name);
            existing.setDescription(description);
            existing.setBasePrice(basePrice);
            existing.setSku(sku);

            Product updated = repo.update(existing);
            log.info("Updated product id={}", id);
            return updated;
        });
    }

    public void delete(UUID id) {
        dsl.transaction(config -> {
            repo.findById(id)
                .orElseThrow(() -> new NotFoundException("Product", id));
            repo.deleteById(id);
            log.info("Deleted product id={}", id);
        });
    }

    // ── Validation

    private void validateProductFields(String name, BigDecimal basePrice, String sku) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("name is required");
        }
        if (basePrice == null || basePrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException("basePrice must be >= 0");
        }
        if (sku == null || sku.isBlank()) {
            throw new ValidationException("sku is required");
        }
    }
}