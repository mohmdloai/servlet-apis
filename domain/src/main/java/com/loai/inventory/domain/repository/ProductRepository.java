package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Product;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository {
  Optional<Product> findById(UUID id);

  List<Product> findAll(int offset, int limit);

  long count();

  Product insert(Product product);

  Product update(Product product);

  void deleteById(UUID id);

  boolean existsBySku(String sku);

  boolean existsBySkuAndIdNot(String sku, UUID excludeId);
}
