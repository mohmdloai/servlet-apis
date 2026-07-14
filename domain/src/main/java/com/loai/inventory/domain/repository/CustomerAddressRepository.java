package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.CustomerAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD over a customer's saved-address book (slice P4, {@code portal_addresses_reorder.md}). Every
 * method is scoped to {@code (orgId, customerId)} — both taken from the session token by the
 * service, never from a request param — so a customer can only ever read or mutate their own rows.
 * The one-default invariant is enforced by the DB (partial unique index); {@link #clearDefault} +
 * {@link #setDefault} must run inside one transaction so promoting a new default is atomic.
 */
public interface CustomerAddressRepository {

  /** The customer's addresses — the default first, then newest — for the address-book list. */
  List<CustomerAddress> findByCustomerId(UUID orgId, UUID customerId);

  /**
   * One address by id, scoped to its owner — the ownership gate for edit / delete / set-default.
   */
  Optional<CustomerAddress> findById(UUID orgId, UUID customerId, UUID id);

  long countByCustomerId(UUID orgId, UUID customerId);

  CustomerAddress insert(CustomerAddress address);

  /** Update the editable content fields (label, recipient, phone, address). */
  CustomerAddress update(CustomerAddress address);

  void deleteById(UUID orgId, UUID customerId, UUID id);

  /**
   * Clear the customer's current default (all rows → {@code is_default = false}). Run before {@link
   * #setDefault} inside one transaction so the partial unique index never sees two defaults.
   */
  void clearDefault(UUID orgId, UUID customerId);

  /** Mark exactly one address as the default (assumes {@link #clearDefault} ran first this txn). */
  void setDefault(UUID orgId, UUID customerId, UUID id);
}
