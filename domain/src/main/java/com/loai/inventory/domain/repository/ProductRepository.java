package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Product;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository {
  Optional<Product> findById(UUID orgId, UUID id);

  /**
   * Resolve a product by its (org-scoped) barcode — the scanner's lookup seam ({@code FLOW.md §3}
   * step 1). Exact match, backed by the {@code product_org_barcode_unique} partial index. {@code
   * barcode} is assumed already trimmed/non-blank by the caller.
   */
  Optional<Product> findByBarcode(UUID orgId, String barcode);

  /**
   * Batch projection {@code id → name} for the given products, scoped to {@code orgId}. Used by
   * reservation reads to decorate rows with the product name without loading whole aggregates — one
   * query per page, not per row. Ids not in {@code orgId} are simply absent from the result map.
   */
  Map<UUID, String> findNamesByIds(UUID orgId, Collection<UUID> ids);

  List<Product> findAll(UUID orgId, int offset, int limit);

  long count(UUID orgId);

  Product insert(Product product);

  Product update(Product product);

  void deleteById(UUID orgId, UUID id);

  boolean existsBySku(UUID orgId, String sku);

  boolean existsBySkuAndIdNot(UUID orgId, String sku, UUID excludeId);

  boolean existsByBarcode(UUID orgId, String barcode);

  boolean existsByBarcodeAndIdNot(UUID orgId, String barcode, UUID excludeId);
}
