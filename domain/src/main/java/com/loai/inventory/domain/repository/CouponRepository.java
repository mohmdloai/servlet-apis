package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Coupon;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Coupons (roadmap item 9, {@code stories/honest_coupons.md}) — the admin CRUD plane plus the two
 * reads the checkout needs: the code lookup, and the live-redemption count that is a query rather
 * than a counter.
 */
public interface CouponRepository {

  // --- admin plane ---

  /** The org's coupons, newest first (paged). */
  List<Coupon> findAll(UUID orgId, int offset, int limit);

  long count(UUID orgId);

  Optional<Coupon> findById(UUID orgId, UUID id);

  /** Plain lookup by normalized code — the advisory preview's read (no row lock). */
  Optional<Coupon> findByCode(UUID orgId, String code);

  boolean existsByCode(UUID orgId, String code);

  Coupon insert(Coupon coupon);

  /**
   * Persist the mutable fields only: {@code active}, {@code expiresAt}, {@code maxRedemptions}.
   * Code, type and value are immutable by design (orders froze them), so this method structurally
   * cannot change them — the guarantee lives in SQL, not just in a service check.
   */
  Coupon updateMutable(Coupon coupon);

  void deleteById(UUID orgId, UUID id);

  // --- placement plane ---

  /**
   * Load a coupon by normalized code <b>FOR UPDATE</b> — the lock that serializes the last-slot
   * race. Two concurrent placements of a cap-1 code queue here, so the second sees the first's
   * order and is refused instead of both succeeding. Must run inside the caller's placement
   * transaction.
   */
  Optional<Coupon> findByCodeForUpdate(UUID orgId, String code);

  /**
   * Live redemptions of one coupon: orders holding it whose status is <b>not</b> CANCELLED/EXPIRED.
   * Counting by query is what makes a cancelled or expired order free its slot automatically —
   * there is no counter column to drift, and no compensating write to forget.
   */
  long countLiveRedemptions(UUID orgId, UUID couponId);

  /**
   * The same count for a set of coupons (batch, no N+1) — the admin list's {@code
   * redemption_count}.
   */
  Map<UUID, Long> liveRedemptionCounts(UUID orgId, Collection<UUID> couponIds);

  /** True if <b>any</b> order (any status) references this coupon — the delete guard. */
  boolean hasAnyRedemption(UUID orgId, UUID couponId);
}
