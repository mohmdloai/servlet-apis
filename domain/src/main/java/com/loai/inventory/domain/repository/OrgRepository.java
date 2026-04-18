package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Org;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgRepository {
  Optional<Org> findById(UUID id);

  Optional<Org> findBySlug(String slug);

  List<Org> findAll(int offset, int limit);

  List<Org> findAllByIds(List<UUID> ids);

  long count();

  Org insert(Org org);

  Org update(Org org);

  void deleteById(UUID id);

  boolean existsBySlug(String slug);
}
