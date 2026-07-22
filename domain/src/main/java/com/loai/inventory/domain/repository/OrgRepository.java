package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Org;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgRepository {
  Optional<Org> findById(UUID id);

  Optional<Org> findBySlug(String slug);

  List<Org> findAll(int offset, int limit);

  /** Paged list, optionally filtered by active status. {@code active == null} returns all. */
  List<Org> findAll(int offset, int limit, Boolean active);

  List<Org> findAllByIds(List<UUID> ids);

  long count();

  /** Total count, optionally filtered by active status. {@code active == null} counts all. */
  long count(Boolean active);

  Org insert(Org org);

  Org update(Org org);

  /**
   * Suspend ({@code suspended=true}: sets {@code active=false}, stamps {@code suspended_at} +
   * reason) or reactivate ({@code suspended=false}: sets {@code active=true}, clears both). Returns
   * the updated org.
   */
  Org setSuspension(UUID orgId, boolean suspended, String reason);

  void deleteById(UUID id);

  boolean existsBySlug(String slug);

  /**
   * Activate the registration-pending orgs of a freshly-verified owner (story 89): every org where
   * {@code ownerId} is the <em>sole</em> member holding OWNER, that is {@code active=false} with
   * {@code suspended_at IS NULL} — i.e. born inactive at self-serve registration, never
   * admin-suspended. Returns the activated ids (for status-mirror invalidation). An admin-suspended
   * org ({@code suspended_at} stamped) is never resurrected here.
   */
  List<UUID> activateRegistrationPendingOrgs(UUID ownerId);
}
