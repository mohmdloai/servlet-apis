package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_INTENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.USER_ORG_ROLE;

import com.loai.inventory.domain.model.OrgHealth;
import com.loai.inventory.domain.repository.OrgHealthRepository;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.PaymentDirection;
import com.loai.inventory.repository.generated.enums.PaymentStatus;
import com.loai.inventory.repository.generated.enums.PaymentVerificationStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

public final class OrgHealthRepositoryImpl implements OrgHealthRepository {

  private final DSLContext dsl;

  public OrgHealthRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public OrgHealth health(UUID orgId) {
    long members =
        dsl.fetchCount(
            dsl.selectDistinct(USER_ORG_ROLE.USER_ID)
                .from(USER_ORG_ROLE)
                .where(USER_ORG_ROLE.ORG_ID.eq(orgId)));
    long pending =
        dsl.fetchCount(
            SALES_ORDER,
            SALES_ORDER.ORG_ID.eq(orgId).and(SALES_ORDER.STATUS.eq(OrderStatus.PENDING_PAYMENT)));
    // Both payment rollups come off the same table, so fold them into one scan with FILTER.
    Field<Integer> disputed =
        DSL.count().filterWhere(PAYMENT.STATUS.eq(PaymentStatus.DISPUTED)).as("disputed");
    Field<Integer> unallocatedField =
        DSL.count().filterWhere(PAYMENT.UNALLOCATED_AMOUNT.gt(BigDecimal.ZERO)).as("unallocated");
    var payments =
        dsl.select(disputed, unallocatedField)
            .from(PAYMENT)
            .where(PAYMENT.ORG_ID.eq(orgId))
            .fetchOne();
    long disputes = payments == null ? 0L : payments.get(disputed).longValue();
    long unallocated = payments == null ? 0L : payments.get(unallocatedField).longValue();
    // The "To verify" queue depth: shopper claims (inbound) a manager has not yet decided on.
    // Served by idx_txn_unverified (V22).
    long claims =
        dsl.fetchCount(
            PAYMENT_TRANSACTION,
            PAYMENT_TRANSACTION
                .ORG_ID
                .eq(orgId)
                .and(PAYMENT_TRANSACTION.DIRECTION.eq(PaymentDirection.CREDIT))
                .and(
                    PAYMENT_TRANSACTION.VERIFICATION_STATUS.eq(
                        PaymentVerificationStatus.UNVERIFIED)));
    // Card intents the Paymob poller could not settle or fail before their own deadline
    // (stories/paymob_card_reliability.md) — idx_payment_intent_pending.
    long stuckIntents =
        dsl.fetchCount(
            PAYMENT_INTENT,
            PAYMENT_INTENT
                .ORG_ID
                .eq(orgId)
                .and(PAYMENT_INTENT.STATUS.eq("PENDING"))
                .and(PAYMENT_INTENT.EXPIRES_AT.lt(OffsetDateTime.now(ZoneOffset.UTC))));
    return new OrgHealth(members, pending, disputes, unallocated, claims, stuckIntents);
  }

  @Override
  public Map<UUID, Long> memberCounts(List<UUID> orgIds) {
    if (orgIds == null || orgIds.isEmpty()) {
      return Map.of();
    }
    Field<Integer> memberCount = DSL.countDistinct(USER_ORG_ROLE.USER_ID).as("member_count");
    Map<UUID, Integer> raw =
        dsl.select(USER_ORG_ROLE.ORG_ID, memberCount)
            .from(USER_ORG_ROLE)
            .where(USER_ORG_ROLE.ORG_ID.in(orgIds))
            .groupBy(USER_ORG_ROLE.ORG_ID)
            .fetchMap(USER_ORG_ROLE.ORG_ID, memberCount);
    Map<UUID, Long> counts = new HashMap<>();
    for (UUID orgId : orgIds) {
      counts.put(orgId, raw.getOrDefault(orgId, 0).longValue());
    }
    return counts;
  }
}
