package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Category;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository {

  Optional<Category> findById(UUID orgId, UUID id);

  Optional<Category> findBySlug(UUID orgId, String slug);

  List<Category> findByIds(UUID orgId, Collection<UUID> ids);

  List<Category> findAll(UUID orgId, int offset, int limit);

  long count(UUID orgId);

  Category insert(Category category);

  Category update(Category category);

  void deleteById(UUID orgId, UUID id);

  boolean existsById(UUID orgId, UUID id);

  boolean existsBySlug(UUID orgId, String slug);

  boolean existsBySlugAndIdNot(UUID orgId, String slug, UUID excludeId);

  /** True if any category in the org names {@code id} as its parent (blocks delete). */
  boolean hasChildren(UUID orgId, UUID id);
}
