package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the SalesOrder aggregate (order + lines), the per-org order-number counter, and
 * product-snapshot reads needed at order placement.
 *
 * <p>Bound to a transactional {@code DSLContext} via {@link SalesOrderRepositoryFactory}. All
 * methods run inside the caller's transaction.
 */
public interface SalesOrderRepository {

  /**
   * A product looked up at order placement, used to snapshot description + unit price onto lines.
   */
  record ProductSnapshot(UUID productId, String description, BigDecimal unitPrice) {}

  Optional<SalesOrder> findById(UUID orgId, UUID id);

  /** Idempotency short-circuit: returns the prior order if this key has already been used. */
  Optional<SalesOrder> findByIdempotencyKey(UUID orgId, String idempotencyKey);

  List<SalesOrderLine> findLinesByOrderId(UUID salesOrderId);

  /**
   * Snapshot lookup for the products on a new order. Returns only products that belong to {@code
   * orgId}; missing ids in the result map signal a 404 to the service layer.
   */
  Map<UUID, ProductSnapshot> fetchProductSnapshots(UUID orgId, Collection<UUID> productIds);

  /**
   * Claim the next sequential order number for {@code (orgId, year)} via {@code INSERT … ON
   * CONFLICT DO UPDATE … RETURNING}. Atomic under concurrent inserts.
   */
  long claimOrderNumber(UUID orgId, int year);

  /**
   * Persist the customer at {@code (org_id, email)}. New row on first sight; otherwise updates
   * name/phone/address using {@code COALESCE(EXCLUDED.x, customer.x)} so a partial payload doesn't
   * erase prior values.
   */
  Customer upsertCustomerByEmail(
      UUID orgId, String email, String name, String phone, String address);

  /** Read a customer for response mapping (e.g. on idempotent replay of a prior order). */
  Optional<Customer> findCustomerById(UUID orgId, UUID customerId);

  /** Insert the order + its lines in one go. Both rows are written inside the caller's txn. */
  void insert(SalesOrder order, List<SalesOrderLine> lines);
}
