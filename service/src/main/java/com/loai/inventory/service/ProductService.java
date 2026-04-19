package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Product;
import com.loai.inventory.domain.repository.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns business rules and transaction boundaries for Product. Every method takes {@code orgId}
 * explicitly so the type system enforces org scoping.
 */
public class ProductService {

  private static final Logger log = LoggerFactory.getLogger(ProductService.class);

  private final ProductRepository repo;
  private final DSLContext dsl;

  public ProductService(ProductRepository repo, DSLContext dsl) {
    this.repo = repo;
    this.dsl = dsl;
  }

  // ── Queries

  public Product getById(UUID orgId, UUID id) {
    return repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Product", id));
  }

  public List<Product> getAll(UUID orgId, int page, int size) {
    if (page < 0) throw new ValidationException("page must be >= 0");
    if (size < 1 || size > 100) throw new ValidationException("size must be 1-100");
    return repo.findAll(orgId, page * size, size);
  }

  public long count(UUID orgId) {
    return repo.count(orgId);
  }

  // ── Commands

  public Product create(
      UUID orgId, String name, String description, BigDecimal basePrice, String sku) {
    validateProductFields(name, basePrice, sku);

    return dsl.transactionResult(
        config -> {
          if (repo.existsBySku(orgId, sku)) {
            throw new ConflictException("SKU already exists: " + sku);
          }

          Product product = new Product();
          product.setOrgId(orgId);
          product.setName(name);
          product.setDescription(description);
          product.setBasePrice(basePrice);
          product.setSku(sku);

          Product saved = repo.insert(product);
          log.info("Created product id={} orgId={} sku={}", saved.getId(), orgId, saved.getSku());
          return saved;
        });
  }

  public Product update(
      UUID orgId, UUID id, String name, String description, BigDecimal basePrice, String sku) {
    validateProductFields(name, basePrice, sku);

    return dsl.transactionResult(
        config -> {
          Product existing =
              repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Product", id));

          if (repo.existsBySkuAndIdNot(orgId, sku, id)) {
            throw new ConflictException("SKU already used by another product: " + sku);
          }

          existing.setName(name);
          existing.setDescription(description);
          existing.setBasePrice(basePrice);
          existing.setSku(sku);

          Product updated = repo.update(existing);
          log.info("Updated product id={} orgId={}", id, orgId);
          return updated;
        });
  }

  public void delete(UUID orgId, UUID id) {
    dsl.transaction(
        config -> {
          repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Product", id));
          repo.deleteById(orgId, id);
          log.info("Deleted product id={} orgId={}", id, orgId);
        });
  }

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
