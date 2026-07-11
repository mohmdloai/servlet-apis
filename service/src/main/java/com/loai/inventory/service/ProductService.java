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
import org.jooq.exception.DataAccessException;
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

  /**
   * Resolve a scanned barcode to its product — the scanner's lookup seam ({@code FLOW.md §3} step
   * 1). Trims the input; a blank barcode is a 400 (the handler only routes here on a non-blank
   * {@code ?barcode=}); no match is a 404 (the "unknown barcode" branch the client turns into
   * create-with-prefill).
   */
  public Product getByBarcode(UUID orgId, String barcode) {
    String normalized = normalizeBarcode(barcode);
    if (normalized == null) {
      throw new ValidationException("barcode is required");
    }
    return repo.findByBarcode(orgId, normalized)
        .orElseThrow(() -> new NotFoundException("Product not found for barcode: " + normalized));
  }

  /**
   * Paged product list, optionally narrowed by a name/SKU search term {@code q} (the POS "search to
   * add" picker). A blank/{@code null} {@code q} returns the whole list — the pre-search behaviour.
   */
  public List<Product> getAll(UUID orgId, String q, int page, int size) {
    if (page < 0) throw new ValidationException("page must be >= 0");
    if (size < 1 || size > 100) throw new ValidationException("size must be 1-100");
    return repo.findAll(orgId, q, page * size, size);
  }

  public long count(UUID orgId, String q) {
    return repo.count(orgId, q);
  }

  // ── Commands

  public Product create(
      UUID orgId,
      String name,
      String description,
      BigDecimal basePrice,
      String sku,
      String barcode) {
    validateProductFields(name, basePrice, sku);
    String normalizedBarcode = normalizeBarcode(barcode);

    return dsl.transactionResult(
        config -> {
          if (repo.existsBySku(orgId, sku)) {
            throw new ConflictException("SKU already exists: " + sku);
          }
          if (normalizedBarcode != null && repo.existsByBarcode(orgId, normalizedBarcode)) {
            throw new ConflictException("Barcode already exists: " + normalizedBarcode);
          }

          Product product = new Product();
          product.setOrgId(orgId);
          product.setName(name);
          product.setDescription(description);
          product.setBasePrice(basePrice);
          product.setSku(sku);
          product.setBarcode(normalizedBarcode);

          Product saved = repo.insert(product);
          log.info("Created product id={} orgId={} sku={}", saved.getId(), orgId, saved.getSku());
          return saved;
        });
  }

  public Product update(
      UUID orgId,
      UUID id,
      String name,
      String description,
      BigDecimal basePrice,
      String sku,
      String barcode) {
    validateProductFields(name, basePrice, sku);
    String normalizedBarcode = normalizeBarcode(barcode);

    return dsl.transactionResult(
        config -> {
          Product existing =
              repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Product", id));

          if (repo.existsBySkuAndIdNot(orgId, sku, id)) {
            throw new ConflictException("SKU already used by another product: " + sku);
          }
          if (normalizedBarcode != null
              && repo.existsByBarcodeAndIdNot(orgId, normalizedBarcode, id)) {
            throw new ConflictException(
                "Barcode already used by another product: " + normalizedBarcode);
          }

          existing.setName(name);
          existing.setDescription(description);
          existing.setBasePrice(basePrice);
          existing.setSku(sku);
          existing.setBarcode(normalizedBarcode);

          Product updated = repo.update(existing);
          log.info("Updated product id={} orgId={}", id, orgId);
          return updated;
        });
  }

  public void delete(UUID orgId, UUID id) {
    dsl.transaction(
        config -> {
          repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Product", id));
          try {
            repo.deleteById(orgId, id);
          } catch (DataAccessException e) {
            // A product still referenced by inventory / listings / order lines raises a Postgres
            // integrity-constraint violation (SQLState class 23). Surface it as a clean 409 rather
            // than letting the raw jOOQ exception bubble to a 500.
            String sqlState = e.sqlState();
            if (sqlState != null && sqlState.startsWith("23")) {
              throw new ConflictException(
                  "Product is referenced by other records (inventory, listings, or orders) and"
                      + " cannot be deleted");
            }
            throw e;
          }
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

  /**
   * Normalize an optional barcode: trim, treat blank as absent ({@code null} → column stays NULL,
   * and the partial unique index allows many NULLs), and cap at the {@code VARCHAR(64)} column
   * width. The barcode is an opaque symbology-agnostic string — no format enforcement.
   */
  private String normalizeBarcode(String barcode) {
    if (barcode == null) {
      return null;
    }
    String trimmed = barcode.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > 64) {
      throw new ValidationException("barcode must be at most 64 characters");
    }
    return trimmed;
  }
}
