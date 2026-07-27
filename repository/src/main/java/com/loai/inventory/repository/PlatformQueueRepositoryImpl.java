package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CREDIT_NOTE;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY_EMAIL;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.REFUND;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;

import com.loai.inventory.domain.model.OrgStatus;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PlatformQueueKind;
import com.loai.inventory.domain.model.PlatformQueueOrg;
import com.loai.inventory.domain.model.PlatformQueueRow;
import com.loai.inventory.domain.repository.PlatformQueueRepository;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.OrderField;
import org.jooq.Record;

/**
 * The paged, oldest-first cross-org queue rows behind {@code GET /api/admin/queues/{kind}}. See
 * {@link PlatformQueueRepository} for the three rules that keep this un-scoped read safe, and
 * {@link PlatformQueuePredicates} for why no membership rule is written here.
 *
 * <p>Every query below is {@code PlatformQueuePredicates.from(kind)} joined to {@code org} (and,
 * for two kinds, to a lookup table for a human-readable number), filtered by {@code
 * PlatformQueuePredicates.where(kind)}. Those extra joins are 0-or-1 lookups across foreign keys,
 * so they cannot move a {@code total} away from the tile it drills into.
 *
 * <p><strong>Every SELECT lists its columns.</strong> Not {@code select()}: rule 3 is a field
 * whitelist, and a star would quietly widen it the next time a column lands on one of these tables
 * — {@code payment_transaction.raw_payload} and {@code notification_delivery_email.rendered_html}
 * are both one star away from an operator's screen.
 */
public final class PlatformQueueRepositoryImpl implements PlatformQueueRepository {

  private final DSLContext dsl;

  public PlatformQueueRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public long count(PlatformQueueKind kind, UUID orgId) {
    return dsl.fetchCount(PlatformQueuePredicates.from(kind), filter(kind, orgId));
  }

  @Override
  public List<PlatformQueueRow> page(PlatformQueueKind kind, UUID orgId, int offset, int limit) {
    Condition where = filter(kind, orgId);
    return switch (kind) {
      case FAILED_EMAILS -> failedEmails(where, offset, limit);
      case PENDING_REFUNDS -> pendingRefunds(where, offset, limit);
      case OPEN_DISPUTES -> openDisputes(where, offset, limit);
      case ORPHAN_TRANSACTIONS -> orphanTransactions(where, offset, limit);
      case EXPIRED_PENDING_ORDERS -> expiredPendingOrders(where, offset, limit);
    };
  }

  /**
   * The queue's own predicate, optionally narrowed to one tenant. {@code orgId} is a
   * <em>filter</em> — an id no tenant holds simply matches nothing, which is an empty page rather
   * than a 404, exactly as {@code credit_note_id} behaves on the refunds worklist.
   *
   * <p>There is deliberately no org-status term. See {@link PlatformQueueOrg}.
   */
  private static Condition filter(PlatformQueueKind kind, UUID orgId) {
    Condition where = PlatformQueuePredicates.where(kind);
    return orgId == null ? where : where.and(PlatformQueuePredicates.orgId(kind).eq(orgId));
  }

  private List<PlatformQueueRow> failedEmails(Condition where, int offset, int limit) {
    PlatformQueueKind kind = PlatformQueueKind.FAILED_EMAILS;
    // LEFT JOIN, not INNER: the email subtype row is the only place to_address lives, but it exists
    // only for the email channel. Left-joining keeps this list identical to the count even if an
    // in-app delivery ever reaches FAILED — such a row would list with no recipient rather than
    // vanish from a queue that still counts it.
    return dsl.select(
            ORG.ID,
            ORG.NAME,
            ORG.SLUG,
            ORG.ACTIVE,
            ORG.SUSPENDED_AT,
            NOTIFICATION_DELIVERY.ID,
            NOTIFICATION.TYPE,
            NOTIFICATION_DELIVERY_EMAIL.TO_ADDRESS,
            NOTIFICATION_DELIVERY.ATTEMPTS,
            NOTIFICATION_DELIVERY.LAST_ERROR,
            NOTIFICATION_DELIVERY.FAILED_AT,
            NOTIFICATION_DELIVERY.CREATED_AT)
        .from(PlatformQueuePredicates.from(kind))
        .join(ORG)
        .on(ORG.ID.eq(PlatformQueuePredicates.orgId(kind)))
        .leftJoin(NOTIFICATION_DELIVERY_EMAIL)
        .on(NOTIFICATION_DELIVERY_EMAIL.DELIVERY_ID.eq(NOTIFICATION_DELIVERY.ID))
        .where(where)
        .orderBy(ordering(kind))
        .offset(offset)
        .limit(limit)
        .fetch(
            r ->
                new PlatformQueueRow.FailedEmail(
                    org(r),
                    r.get(NOTIFICATION_DELIVERY.ID),
                    r.get(NOTIFICATION.TYPE),
                    r.get(NOTIFICATION_DELIVERY_EMAIL.TO_ADDRESS),
                    r.get(NOTIFICATION_DELIVERY.ATTEMPTS),
                    r.get(NOTIFICATION_DELIVERY.LAST_ERROR),
                    r.get(NOTIFICATION_DELIVERY.FAILED_AT),
                    r.get(NOTIFICATION_DELIVERY.CREATED_AT)));
  }

  private List<PlatformQueueRow> pendingRefunds(Condition where, int offset, int limit) {
    PlatformQueueKind kind = PlatformQueueKind.PENDING_REFUNDS;
    // A refund is backed by exactly one of a payment or a credit note (refund_check), so exactly
    // one
    // of these two numbers resolves — the same source context the org-scoped refunds worklist
    // batch-loads onto its rows. A payment for an orphan transfer has no order, so even the
    // payment-backed number is legitimately absent sometimes.
    return dsl.select(
            ORG.ID,
            ORG.NAME,
            ORG.SLUG,
            ORG.ACTIVE,
            ORG.SUSPENDED_AT,
            REFUND.ID,
            REFUND.AMOUNT,
            REFUND.METHOD,
            SALES_ORDER.ORDER_NUMBER,
            CREDIT_NOTE.CREDIT_NOTE_NUMBER,
            REFUND.CREATED_AT)
        .from(PlatformQueuePredicates.from(kind))
        .join(ORG)
        .on(ORG.ID.eq(PlatformQueuePredicates.orgId(kind)))
        .leftJoin(PAYMENT)
        .on(PAYMENT.ID.eq(REFUND.PAYMENT_ID))
        .leftJoin(SALES_ORDER)
        .on(SALES_ORDER.ID.eq(PAYMENT.SALES_ORDER_ID))
        .leftJoin(CREDIT_NOTE)
        .on(CREDIT_NOTE.ID.eq(REFUND.CREDIT_NOTE_ID))
        .where(where)
        .orderBy(ordering(kind))
        .offset(offset)
        .limit(limit)
        .fetch(
            r ->
                new PlatformQueueRow.PendingRefund(
                    org(r),
                    r.get(REFUND.ID),
                    r.get(REFUND.AMOUNT),
                    provider(r.get(REFUND.METHOD)),
                    r.get(SALES_ORDER.ORDER_NUMBER),
                    r.get(CREDIT_NOTE.CREDIT_NOTE_NUMBER),
                    r.get(REFUND.CREATED_AT)));
  }

  private List<PlatformQueueRow> openDisputes(Condition where, int offset, int limit) {
    PlatformQueueKind kind = PlatformQueueKind.OPEN_DISPUTES;
    // dispute_reason is the merchant's own note on the payment and is not carried: the operator's
    // question here is "which tenant is sitting on frozen money, and since when".
    return dsl.select(
            ORG.ID,
            ORG.NAME,
            ORG.SLUG,
            ORG.ACTIVE,
            ORG.SUSPENDED_AT,
            PAYMENT.ID,
            PAYMENT.AMOUNT,
            SALES_ORDER.ORDER_NUMBER,
            PAYMENT.RECEIVED_AT)
        .from(PlatformQueuePredicates.from(kind))
        .join(ORG)
        .on(ORG.ID.eq(PlatformQueuePredicates.orgId(kind)))
        .leftJoin(SALES_ORDER)
        .on(SALES_ORDER.ID.eq(PAYMENT.SALES_ORDER_ID))
        .where(where)
        .orderBy(ordering(kind))
        .offset(offset)
        .limit(limit)
        .fetch(
            r ->
                new PlatformQueueRow.OpenDispute(
                    org(r),
                    r.get(PAYMENT.ID),
                    r.get(PAYMENT.AMOUNT),
                    r.get(SALES_ORDER.ORDER_NUMBER),
                    r.get(PAYMENT.RECEIVED_AT)));
  }

  private List<PlatformQueueRow> orphanTransactions(Condition where, int offset, int limit) {
    PlatformQueueKind kind = PlatformQueueKind.ORPHAN_TRANSACTIONS;
    // provider_ref is the reference an operator searches the provider's own statement by — it is
    // the
    // whole triage. proof_object_key, raw_payload, customer_note and claimed_by_customer_id are
    // deliberately absent.
    return dsl.select(
            ORG.ID,
            ORG.NAME,
            ORG.SLUG,
            ORG.ACTIVE,
            ORG.SUSPENDED_AT,
            PAYMENT_TRANSACTION.ID,
            PAYMENT_TRANSACTION.AMOUNT,
            PAYMENT_TRANSACTION.PROVIDER,
            PAYMENT_TRANSACTION.PROVIDER_REF,
            PAYMENT_TRANSACTION.OCCURRED_AT)
        .from(PlatformQueuePredicates.from(kind))
        .join(ORG)
        .on(ORG.ID.eq(PlatformQueuePredicates.orgId(kind)))
        .where(where)
        .orderBy(ordering(kind))
        .offset(offset)
        .limit(limit)
        .fetch(
            r ->
                new PlatformQueueRow.OrphanTransaction(
                    org(r),
                    r.get(PAYMENT_TRANSACTION.ID),
                    r.get(PAYMENT_TRANSACTION.AMOUNT),
                    provider(r.get(PAYMENT_TRANSACTION.PROVIDER)),
                    r.get(PAYMENT_TRANSACTION.PROVIDER_REF),
                    r.get(PAYMENT_TRANSACTION.OCCURRED_AT)));
  }

  private List<PlatformQueueRow> expiredPendingOrders(Condition where, int offset, int limit) {
    PlatformQueueKind kind = PlatformQueueKind.EXPIRED_PENDING_ORDERS;
    // No customer_id, no notes, no lines: an expired hold is triaged by whose store it is, how much
    // stock it is holding hostage, and how long ago it lapsed.
    return dsl.select(
            ORG.ID,
            ORG.NAME,
            ORG.SLUG,
            ORG.ACTIVE,
            ORG.SUSPENDED_AT,
            SALES_ORDER.ID,
            SALES_ORDER.ORDER_NUMBER,
            SALES_ORDER.GRAND_TOTAL,
            SALES_ORDER.EXPIRES_AT,
            SALES_ORDER.PLACED_AT)
        .from(PlatformQueuePredicates.from(kind))
        .join(ORG)
        .on(ORG.ID.eq(PlatformQueuePredicates.orgId(kind)))
        .where(where)
        .orderBy(ordering(kind))
        .offset(offset)
        .limit(limit)
        .fetch(
            r ->
                new PlatformQueueRow.ExpiredPendingOrder(
                    org(r),
                    r.get(SALES_ORDER.ID),
                    r.get(SALES_ORDER.ORDER_NUMBER),
                    r.get(SALES_ORDER.GRAND_TOTAL),
                    r.get(SALES_ORDER.EXPIRES_AT),
                    r.get(SALES_ORDER.PLACED_AT)));
  }

  /** Oldest-first on the queue's own clock, tie-broken by id so paging is stable. */
  private static OrderField<?>[] ordering(PlatformQueueKind kind) {
    return new OrderField<?>[] {
      PlatformQueuePredicates.orderKey(kind).asc(), PlatformQueuePredicates.id(kind).asc()
    };
  }

  /**
   * The tenant, with the state named by the server. {@link OrgStatus#of} is the same derivation the
   * org list and the tenant tiles run — no second copy of the rule, and never a boolean for a
   * client to re-derive from.
   */
  private static PlatformQueueOrg org(Record r) {
    return new PlatformQueueOrg(
        r.get(ORG.ID),
        r.get(ORG.NAME),
        r.get(ORG.SLUG),
        OrgStatus.of(Boolean.TRUE.equals(r.get(ORG.ACTIVE)), r.get(ORG.SUSPENDED_AT)));
  }

  /** The DB's lowercase provider label back to the domain enum's name, as every DTO reports it. */
  private static String provider(
      com.loai.inventory.repository.generated.enums.PaymentProvider generated) {
    return generated == null ? null : PaymentProvider.fromDbLiteral(generated.getLiteral()).name();
  }
}
