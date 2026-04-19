package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Product;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository {
  Optional<Product> findById(UUID orgId, UUID id);

  List<Product> findAll(UUID orgId, int offset, int limit);

  long count(UUID orgId);

  Product insert(Product product);

  Product update(Product product);

  void deleteById(UUID orgId, UUID id);

  boolean existsBySku(UUID orgId, String sku);

  boolean existsBySkuAndIdNot(UUID orgId, String sku, UUID excludeId);
}
