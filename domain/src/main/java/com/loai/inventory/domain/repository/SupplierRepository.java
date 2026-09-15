package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Supplier;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface SupplierRepository {

  Optional<Supplier> findById(UUID orgId, UUID id);

  /** Batch load by id — one query per page for the receipt lists that name their supplier. */
  Map<UUID, Supplier> findByIds(UUID orgId, Collection<UUID> ids);

  /**
   * One page of the directory: org ∧ [fold(name) ~ fold(q) ∨ email ~ q] ∧ [active = flag].
   *
   * <p><b>Ordering is {@code name ASC, id ASC} always</b> — the deliberate opposite of {@code
   * CustomerRepository#findAll}'s newest-first. A customer directory is thousands of rows read
   * newest-first; a supplier directory is tens of rows read alphabetically, the paper list on the
   * wall. Both deviations are stated so neither gets "fixed" into the other.
   *
   * <p>Phone is not searchable, the pin {@code search_doesNotMatchPhone} holds for customers.
   */
  List<Supplier> findAll(UUID orgId, String q, Boolean active, int offset, int limit);

  /** The matching count for {@link #findAll} — the same predicate, so rows and total agree. */
  long count(UUID orgId, String q, Boolean active);

  /** The supplier already holding {@code fold_search(name)} in this org, if any. */
  Optional<Supplier> findByFoldedName(UUID orgId, String name);

  Supplier insert(Supplier supplier);

  Supplier update(Supplier supplier);

  void deleteById(UUID orgId, UUID id);
}
