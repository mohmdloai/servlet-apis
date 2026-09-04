package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.Product;
import com.loai.inventory.domain.repository.ProductRepository;
import com.loai.inventory.domain.repository.ProductVariantRepositoryFactory;
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
  private final ProductVariantRepositoryFactory variantRepoFactory;
  private final DSLContext dsl;

  public ProductService(
      ProductRepository repo, ProductVariantRepositoryFactory variantRepoFactory, DSLContext dsl) {
    this.repo = repo;
    this.variantRepoFactory = variantRepoFactory;
    this.dsl = dsl;
  }

  // Queries

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

  // Commands

  /**
   * A cost on a product write, as a tri-state (stories/product_cost_and_margin.md): the key absent
   * from the body leaves the cost as it is ({@link #unchanged()}); present and {@code null} clears
   * it; present with a value sets it. Jackson cannot tell absent from null on a plain field, which
   * is why the request DTOs track presence in the setter and hand this object over — and why the
   * STAFF product form can keep doing a full-replace PUT without the key and never touch a cost it
   * is not allowed to see.
   */
  public record CostPriceChange(boolean present, BigDecimal value) {
    public static CostPriceChange unchanged() {
      return new CostPriceChange(false, null);
    }

    /** Present on the wire: {@code null} clears, a value sets. */
    public static CostPriceChange to(BigDecimal value) {
      return new CostPriceChange(true, value);
    }
  }

  /**
   * The reorder point on the wire (V94). The api layer always passes it as present — the field is
   * full-replace like {@code barcode} — while the pre-V94 service overloads pass {@code
   * unchanged()} so a caller that never knew the column cannot clear it.
   */
  public record ReorderPointChange(boolean present, Integer value) {
    public static ReorderPointChange unchanged() {
      return new ReorderPointChange(false, null);
    }

    /** Present on the wire: {@code null} clears, a value sets. */
    public static ReorderPointChange to(Integer value) {
      return new ReorderPointChange(true, value);
    }
  }

  private static void validateReorderPoint(Integer reorderPoint) {
    if (reorderPoint != null && reorderPoint < 0) {
      throw new ValidationException("reorder_point must be >= 0");
    }
  }

  /** Create without touching cost — the pre-V92 shape, kept for existing callers and tests. */
  public Product create(
      UUID orgId,
      String name,
      String description,
      BigDecimal basePrice,
      String sku,
      String barcode) {
    return create(orgId, name, description, basePrice, sku, barcode, CostPriceChange.unchanged());
  }

  /** Create without a reorder point — the pre-V94 shape, kept for existing callers and tests. */
  public Product create(
      UUID orgId,
      String name,
      String description,
      BigDecimal basePrice,
      String sku,
      String barcode,
      CostPriceChange costPrice) {
    return create(orgId, name, description, basePrice, sku, barcode, costPrice, null);
  }

  /**
   * @param reorderPoint V94 ({@code stories/reorder_point.md}): notify staff when available stock
   *     reaches or falls below it; {@code null} = no rule; negative → 400.
   */
  public Product create(
      UUID orgId,
      String name,
      String description,
      BigDecimal basePrice,
      String sku,
      String barcode,
      CostPriceChange costPrice,
      Integer reorderPoint) {
    String normalizedName = Text.normalizeText(name);
    String normalizedDescription = Text.normalizeText(description);
    validateProductFields(normalizedName, basePrice, sku);
    validateReorderPoint(reorderPoint);
    BigDecimal normalizedCost = normalizeCostPrice(costPrice);
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
          product.setName(normalizedName);
          product.setDescription(normalizedDescription);
          product.setBasePrice(basePrice);
          // Absent on create means "not costed" — there is nothing to leave unchanged yet.
          product.setCostPrice(costPrice.present() ? normalizedCost : null);
          product.setSku(sku);
          product.setBarcode(normalizedBarcode);
          product.setReorderPoint(reorderPoint);

          Product saved = repo.insert(product);
          log.info("Created product id={} orgId={} sku={}", saved.getId(), orgId, saved.getSku());
          return saved;
        });
  }

  /** Update without touching cost — the pre-V92 shape, kept for existing callers and tests. */
  public Product update(
      UUID orgId,
      UUID id,
      String name,
      String description,
      BigDecimal basePrice,
      String sku,
      String barcode) {
    return update(
        orgId, id, name, description, basePrice, sku, barcode, CostPriceChange.unchanged());
  }

  /** Update without touching the reorder point — the pre-V94 shape, kept for existing callers. */
  public Product update(
      UUID orgId,
      UUID id,
      String name,
      String description,
      BigDecimal basePrice,
      String sku,
      String barcode,
      CostPriceChange costPrice) {
    return update(
        orgId,
        id,
        name,
        description,
        basePrice,
        sku,
        barcode,
        costPrice,
        ReorderPointChange.unchanged());
  }

  /**
   * Full update. {@code reorderPoint} is full-replace like {@code barcode} when present on the wire
   * ({@code null} clears); the pre-V94 overload leaves it untouched.
   */
  public Product update(
      UUID orgId,
      UUID id,
      String name,
      String description,
      BigDecimal basePrice,
      String sku,
      String barcode,
      CostPriceChange costPrice,
      ReorderPointChange reorderPoint) {
    String normalizedName = Text.normalizeText(name);
    String normalizedDescription = Text.normalizeText(description);
    validateProductFields(normalizedName, basePrice, sku);
    if (reorderPoint.present()) {
      validateReorderPoint(reorderPoint.value());
    }
    BigDecimal normalizedCost = normalizeCostPrice(costPrice);
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

          existing.setName(normalizedName);
          existing.setDescription(normalizedDescription);
          existing.setBasePrice(basePrice);
          if (costPrice.present()) {
            existing.setCostPrice(normalizedCost);
          }
          existing.setSku(sku);
          existing.setBarcode(normalizedBarcode);
          if (reorderPoint.present()) {
            existing.setReorderPoint(reorderPoint.value());
          }

          Product updated = repo.update(existing);
          log.info("Updated product id={} orgId={}", id, orgId);
          return updated;
        });
  }

  public void delete(UUID orgId, UUID id) {
    dsl.transaction(
        config -> {
          repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Product", id));
          // A listing's PARENT product cannot be deleted while its variant set is live: the
          // children
          // are still on sale through it (architecture §5 #12). Name the remedy rather than letting
          // the shopper-visible set be orphaned. Child products themselves keep today's rules — the
          // FK violation below is what stops one that is referenced by inventory or order lines.
          if (variantRepoFactory
              .create(org.jooq.impl.DSL.using(config))
              .existsActiveVariantForParentProduct(orgId, id)) {
            throw new ConflictException(
                "Product has active variants and cannot be deleted — deactivate its variants first");
          }
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

  /**
   * Validate and scale a present cost; {@code null} (absent or clear) passes through. Scaled to
   * money here so the stored value and the echoed value agree from the first read.
   */
  private static BigDecimal normalizeCostPrice(CostPriceChange change) {
    if (change == null || !change.present() || change.value() == null) {
      return null;
    }
    if (change.value().compareTo(BigDecimal.ZERO) < 0) {
      throw new ValidationException("cost_price must be >= 0");
    }
    return change.value().setScale(2, java.math.RoundingMode.HALF_EVEN);
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
   * Normalize an optional barcode through the canonical numeric-identifier form ({@link
   * Text#normalizeNumeric}): NFC, fold Arabic-Indic/Persian digits to ASCII, strip invisible
   * bidi/zero-width controls, collapse whitespace, blank → absent ({@code null} → column stays
   * NULL, and the partial unique index allows many NULLs). Cap at 64 <em>code points</em> (never
   * bytes — the column is symbology-agnostic and an Arabic digit is multiple UTF-8 bytes). No
   * format enforcement beyond the width.
   */
  private String normalizeBarcode(String barcode) {
    String normalized = Text.normalizeNumeric(barcode);
    if (normalized == null) {
      return null;
    }
    if (normalized.codePointCount(0, normalized.length()) > 64) {
      throw new ValidationException("barcode must be at most 64 characters");
    }
    return normalized;
  }
}
