package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.COUPON;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.Coupon;
import com.loai.inventory.domain.model.CouponType;
import com.loai.inventory.domain.repository.CouponRepository;
import com.loai.inventory.repository.generated.tables.records.CouponRecord;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coupons (roadmap item 9) — CRUD, the locked placement lookup, and the redemption-count queries.
 */
public final class CouponRepositoryImpl implements CouponRepository {
  private static final Logger log = LoggerFactory.getLogger(CouponRepositoryImpl.class);

  /**
   * The statuses that do <b>not</b> hold a redemption slot. A cancelled or expired order gives its
   * slot back — that is the whole reason redemptions are counted rather than incremented.
   */
  private static final com.loai.inventory.repository.generated.enums.OrderStatus[]
      FREED_STATUSES = {
    com.loai.inventory.repository.generated.enums.OrderStatus.CANCELLED,
    com.loai.inventory.repository.generated.enums.OrderStatus.EXPIRED,
  };

  private final DSLContext dsl;

  public CouponRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public List<Coupon> findAll(UUID orgId, int offset, int limit) {
    return dsl.selectFrom(COUPON)
        .where(COUPON.ORG_ID.eq(orgId))
        .orderBy(COUPON.CREATED_AT.desc(), COUPON.CODE.asc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(CouponRepositoryImpl::toCoupon);
  }

  @Override
  public long count(UUID orgId) {
    return dsl.fetchCount(dsl.selectFrom(COUPON).where(COUPON.ORG_ID.eq(orgId)));
  }

  @Override
  public Optional<Coupon> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(COUPON)
        .where(COUPON.ORG_ID.eq(orgId).and(COUPON.ID.eq(id)))
        .fetchOptional()
        .map(CouponRepositoryImpl::toCoupon);
  }

  @Override
  public Optional<Coupon> findByCode(UUID orgId, String code) {
    return dsl.selectFrom(COUPON)
        .where(COUPON.ORG_ID.eq(orgId).and(COUPON.CODE.eq(code)))
        .fetchOptional()
        .map(CouponRepositoryImpl::toCoupon);
  }

  @Override
  public boolean existsByCode(UUID orgId, String code) {
    return dsl.fetchExists(
        dsl.selectOne().from(COUPON).where(COUPON.ORG_ID.eq(orgId).and(COUPON.CODE.eq(code))));
  }

  @Override
  public Coupon insert(Coupon coupon) {
    CouponRecord record =
        dsl.insertInto(COUPON)
            .set(COUPON.ORG_ID, coupon.getOrgId())
            .set(COUPON.CODE, coupon.getCode())
            .set(COUPON.TYPE, coupon.getType().name())
            .set(COUPON.VALUE, coupon.getValue())
            .set(COUPON.MIN_SUBTOTAL, coupon.getMinSubtotal())
            .set(COUPON.STARTS_AT, coupon.getStartsAt())
            .set(COUPON.EXPIRES_AT, coupon.getExpiresAt())
            .set(COUPON.MAX_REDEMPTIONS, coupon.getMaxRedemptions())
            .set(COUPON.ACTIVE, coupon.isActive())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT into coupon returned no record");
    }
    log.debug(
        "Inserted coupon id={} orgId={} code={}",
        record.getId(),
        record.getOrgId(),
        record.getCode());
    return toCoupon(record);
  }

  @Override
  public Coupon updateMutable(Coupon coupon) {
    // Only the three mutable knobs are in this statement — code/type/value are immutable once an
    // order can have frozen them, and leaving them out of the SQL is a stronger guarantee than a
    // service-layer check alone.
    CouponRecord record =
        dsl.update(COUPON)
            .set(COUPON.ACTIVE, coupon.isActive())
            .set(COUPON.EXPIRES_AT, coupon.getExpiresAt())
            .set(COUPON.MAX_REDEMPTIONS, coupon.getMaxRedemptions())
            .set(COUPON.UPDATED_AT, OffsetDateTime.now())
            .where(COUPON.ORG_ID.eq(coupon.getOrgId()).and(COUPON.ID.eq(coupon.getId())))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new NotFoundException("Coupon", coupon.getId());
    }
    return toCoupon(record);
  }

  @Override
  public void deleteById(UUID orgId, UUID id) {
    int deleted =
        dsl.deleteFrom(COUPON).where(COUPON.ORG_ID.eq(orgId).and(COUPON.ID.eq(id))).execute();
    if (deleted == 0) {
      throw new NotFoundException("Coupon", id);
    }
  }

  @Override
  public Optional<Coupon> findByCodeForUpdate(UUID orgId, String code) {
    return dsl.selectFrom(COUPON)
        .where(COUPON.ORG_ID.eq(orgId).and(COUPON.CODE.eq(code)))
        .forUpdate()
        .fetchOptional()
        .map(CouponRepositoryImpl::toCoupon);
  }

  @Override
  public long countLiveRedemptions(UUID orgId, UUID couponId) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from(SALES_ORDER)
            .where(
                SALES_ORDER
                    .ORG_ID
                    .eq(orgId)
                    .and(SALES_ORDER.COUPON_ID.eq(couponId))
                    .and(SALES_ORDER.STATUS.notIn(FREED_STATUSES))));
  }

  @Override
  public Map<UUID, Long> liveRedemptionCounts(UUID orgId, Collection<UUID> couponIds) {
    if (couponIds == null || couponIds.isEmpty()) {
      return Map.of();
    }
    org.jooq.Field<Integer> total = org.jooq.impl.DSL.count();
    Map<UUID, Long> out = new HashMap<>();
    dsl.select(SALES_ORDER.COUPON_ID, total)
        .from(SALES_ORDER)
        .where(
            SALES_ORDER
                .ORG_ID
                .eq(orgId)
                .and(SALES_ORDER.COUPON_ID.in(couponIds))
                .and(SALES_ORDER.STATUS.notIn(FREED_STATUSES)))
        .groupBy(SALES_ORDER.COUPON_ID)
        .fetch()
        .forEach(
            r ->
                out.put(
                    r.get(SALES_ORDER.COUPON_ID),
                    r.get(total) == null ? 0L : r.get(total).longValue()));
    return out;
  }

  @Override
  public boolean hasAnyRedemption(UUID orgId, UUID couponId) {
    // ANY status, including cancelled/expired: those orders still name the code on their record, so
    // deleting the row would orphan the FK. Deactivation is the way to retire a used code.
    return dsl.fetchExists(
        dsl.selectOne()
            .from(SALES_ORDER)
            .where(SALES_ORDER.ORG_ID.eq(orgId).and(SALES_ORDER.COUPON_ID.eq(couponId))));
  }

  private static Coupon toCoupon(CouponRecord r) {
    return new Coupon(
        r.getId(),
        r.getOrgId(),
        r.getCode(),
        CouponType.valueOf(r.getType()),
        r.getValue(),
        r.getMinSubtotal(),
        r.getStartsAt(),
        r.getExpiresAt(),
        r.getMaxRedemptions(),
        Boolean.TRUE.equals(r.getActive()),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }
}
