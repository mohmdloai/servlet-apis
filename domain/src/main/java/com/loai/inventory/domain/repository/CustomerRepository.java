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

  long count(UUID orgId);

  Customer insert(Customer customer);

  Customer update(Customer customer);

  void deleteById(UUID orgId, UUID id);

  boolean existsByEmail(UUID orgId, String email);

  boolean existsByEmailAndIdNot(UUID orgId, String email, UUID excludeId);
}
