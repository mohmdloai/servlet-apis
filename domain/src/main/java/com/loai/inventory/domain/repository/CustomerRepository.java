package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Customer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository {

  Optional<Customer> findById(UUID orgId, UUID id);

  /**
   * Batch load by id, scoped to {@code orgId} — one query per page for list reads that name the
   * customer on each row (the claims queue, {@code stories/payment_claim_verify.md}). Ids outside
   * {@code orgId} are absent from the result.
   */
  Map<UUID, Customer> findByIds(UUID orgId, Collection<UUID> ids);

  /**
   * Learn this customer's language <b>only if we do not already know it</b> (slice L) — returns
   * true when a value was written.
   *
   * <p>Deliberately its own narrow verb rather than a field on the general {@code update}: the
   * caller is a checkout, and a checkout must not be able to touch anything else on the identity
   * row (V80's lesson). Fill-once for the same reason the upsert is: a checkout locale is implicit,
   * from whichever link the shopper opened, while {@code PATCH /api/portal/me} is explicit and
   * overwrites.
   */
  boolean fillLocaleIfAbsent(UUID orgId, UUID id, String locale);

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
