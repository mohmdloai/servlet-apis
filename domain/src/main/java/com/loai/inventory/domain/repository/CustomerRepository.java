package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Customer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository {

  Optional<Customer> findById(UUID orgId, UUID id);

  /**
   * Resolve the single {@code customer} for {@code (orgId, email)} — the portal-login identity bind
   * ({@code customer} is unique per {@code (org_id, email)}). Empty when no such customer exists
   * (an unknown email simply never receives a code — no signup mints a customer here).
   */
  Optional<Customer> findByEmail(UUID orgId, String email);

  /**
   * Stamp {@code email_verified_at} on first proof of ownership (portal OTP verify). Idempotent —
   * later verifies leave the original timestamp untouched (only stamps when currently null), so it
   * records the <em>first</em> proof.
   */
  void markEmailVerified(UUID orgId, UUID id, java.time.OffsetDateTime verifiedAt);

  List<Customer> findAll(UUID orgId, int offset, int limit);

  /**
   * One page of the org's customer directory, optionally narrowed by {@code q} (matched against
   * name and email, OR'd). Blank/null {@code q} is the unfiltered list.
   *
   * <p><b>Ordering is {@code created_at DESC} always, including under {@code q}.</b> Every other
   * paged read in this codebase switches ordering on a filter (the queue-vs-ledger convention), so
   * this deviation will look like an oversight — it is not. This is a directory, not a worklist,
   * and a list that re-sorts itself while the operator types is disorienting for no gain.
   */
  List<Customer> findAll(UUID orgId, String q, int offset, int limit);

  long count(UUID orgId);

  /** The matching count for {@link #findAll(UUID, String, int, int)} — the same predicate. */
  long count(UUID orgId, String q);

  Customer insert(Customer customer);

  Customer update(Customer customer);

  void deleteById(UUID orgId, UUID id);

  boolean existsByEmail(UUID orgId, String email);

  boolean existsByEmailAndIdNot(UUID orgId, String email, UUID excludeId);
}
