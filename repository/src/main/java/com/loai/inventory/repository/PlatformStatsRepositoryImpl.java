package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.JOBRUNR_BACKGROUNDJOBSERVERS;
import static com.loai.inventory.repository.generated.Tables.JOBRUNR_JOBS;
import static com.loai.inventory.repository.generated.Tables.JOBRUNR_RECURRING_JOBS;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.REFUND;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;

import com.loai.inventory.domain.model.OrgStatus;
import com.loai.inventory.domain.model.PlatformQueueCounts;
import com.loai.inventory.domain.model.PlatformTenantCounts;
import com.loai.inventory.domain.model.RecurringJobStats;
import com.loai.inventory.domain.repository.PlatformStatsRepository;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.PaymentReconciliationStatus;
import com.loai.inventory.repository.generated.enums.PaymentStatus;
import com.loai.inventory.repository.generated.enums.RefundStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * The cross-org rollups behind {@code GET /api/admin/overview}. See {@link PlatformStatsRepository}
 * for why this is the one class in the codebase whose reads deliberately omit {@code org_id} — and
 * for the three rules (platform-gated, counts-only, read-only) that keep that safe.
 *
 * <p>Two JobRunr facts are load-bearing here and would bite silently if lost:
 *
 * <ul>
 *   <li><strong>The columns are lowercase.</strong> V29's DDL writes {@code recurringJobId} /
 *       {@code updatedAt} unquoted, so Postgres folded them to {@code recurringjobid} / {@code
 *       updatedat} and jOOQ generated them that way. That is why the constants below read oddly.
 *   <li><strong>{@code createdat}/{@code updatedat} are {@code TIMESTAMP}, not {@code
 *       TIMESTAMPTZ}.</strong> JobRunr writes UTC into a naive column, so each value comes back as
 *       a {@link LocalDateTime} and is given {@link ZoneOffset#UTC} explicitly. Letting the JVM
 *       default zone interpret it would shift every job timestamp by the server's offset.
 * </ul>
 */
public final class PlatformStatsRepositoryImpl implements PlatformStatsRepository {

  /** {@code org.created_at} is TIMESTAMPTZ; Postgres needs an interval, not a bare integer. */
  private static final Field<OffsetDateTime> SEVEN_DAYS_AGO =
      DSL.field("now() - interval '7 days'", OffsetDateTime.class);

  private final DSLContext dsl;

  public PlatformStatsRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public PlatformTenantCounts tenantCounts() {
    // One scan of `org`, five figures — FILTER beats five round trips on a table this small.
    //
    // Every status FILTER is OrgStatusConditions.matching(…), which is the same predicate the org
    // list's `?status=` filter runs (OrgRepositoryImpl.findAll/count), built from OrgStatus's own
    // constants. So the tile and the list it drills into cannot disagree — the rule that outranks
    // the rest here — and `active + suspended + pending == total` holds by construction, because
    // the three statuses partition the table.
    //
    // `suspended` used to be plain `active=false`, which silently included every tenant born
    // inactive at self-serve registration and not yet email-verified (story 88). A person who has
    // not clicked an email must never trip an operational alarm; that is now `pending`. See
    // stories/platform_tenant_states.md.
    Field<Integer> total = DSL.count().as("total");
    Field<Integer> active =
        DSL.count().filterWhere(OrgStatusConditions.matching(OrgStatus.ACTIVE)).as("active");
    Field<Integer> suspended =
        DSL.count().filterWhere(OrgStatusConditions.matching(OrgStatus.SUSPENDED)).as("suspended");
    Field<Integer> pending =
        DSL.count().filterWhere(OrgStatusConditions.matching(OrgStatus.PENDING)).as("pending");
    Field<Integer> recent = DSL.count().filterWhere(ORG.CREATED_AT.ge(SEVEN_DAYS_AGO)).as("recent");
    Record row = dsl.select(total, active, suspended, pending, recent).from(ORG).fetchOne();
    if (row == null) {
      return new PlatformTenantCounts(0, 0, 0, 0, 0);
    }
    return new PlatformTenantCounts(
        row.get(total).longValue(),
        row.get(active).longValue(),
        row.get(suspended).longValue(),
        row.get(pending).longValue(),
        row.get(recent).longValue());
  }

  @Override
  public PlatformQueueCounts queueCounts() {
    // Five tables, five counts. Each predicate is the org-scoped list's predicate verbatim, minus
    // that list's org filter — nothing else about it changes, so tile and drill-down cannot drift.
    //
    // `failed_emails` is `status='FAILED'` with no channel narrowing, per the story. Only the email
    // leg has a failure path today (`NotificationService.dispatchPendingEmail`); the in-app leg
    // goes PENDING→SENT and never reaches FAILED, so the two predicates coincide. If an in-app
    // failure path is ever added, this one needs `AND channel='email'` to keep matching its name.
    long failedEmails = count(NOTIFICATION_DELIVERY, NOTIFICATION_DELIVERY.STATUS.eq("FAILED"));
    long pendingRefunds = count(REFUND, REFUND.STATUS.eq(RefundStatus.PENDING));
    long openDisputes = count(PAYMENT, PAYMENT.STATUS.eq(PaymentStatus.DISPUTED));
    long orphanTransactions =
        count(
            PAYMENT_TRANSACTION,
            PAYMENT_TRANSACTION
                .RECONCILIATION_STATUS
                .eq(PaymentReconciliationStatus.ORPHAN)
                // The org queue's has_payment=false verbatim: the 1:1 payment row is the
                // disposition marker, so a resolved or refunded orphan has already left the queue.
                .and(
                    DSL.notExists(
                        DSL.selectOne()
                            .from(PAYMENT)
                            .where(PAYMENT.PAYMENT_TRANSACTION_ID.eq(PAYMENT_TRANSACTION.ID)))));
    long expiredPendingOrders =
        count(
            SALES_ORDER,
            SALES_ORDER
                .STATUS
                .eq(OrderStatus.PENDING_PAYMENT)
                // Served by idx_so_pending_global (V29) — the sweeper's own candidate predicate.
                .and(SALES_ORDER.EXPIRES_AT.lt(DSL.currentOffsetDateTime())));
    return new PlatformQueueCounts(
        failedEmails, pendingRefunds, openDisputes, orphanTransactions, expiredPendingOrders);
  }

  @Override
  public List<RecurringJobStats> recurringJobStats(List<String> jobIds) {
    if (jobIds == null || jobIds.isEmpty()) {
      return List.of();
    }
    // The registered set is whatever JobRunr actually holds. An id we schedule in code but that is
    // missing from the table is simply absent from the answer — never a fabricated row.
    List<String> registered =
        dsl
            .select(JOBRUNR_RECURRING_JOBS.ID)
            .from(JOBRUNR_RECURRING_JOBS)
            .where(JOBRUNR_RECURRING_JOBS.ID.in(jobIds))
            .fetch(JOBRUNR_RECURRING_JOBS.ID)
            .stream()
            .filter(Objects::nonNull)
            // `id` is CHAR(128), so Postgres blank-pads whatever it returns.
            .map(String::trim)
            .toList();
    if (registered.isEmpty()) {
      return List.of();
    }

    Map<String, RecurringJobStats> byId = outcomes(registered);
    List<RecurringJobStats> ordered = new ArrayList<>();
    for (String id : jobIds) {
      if (registered.contains(id)) {
        ordered.add(byId.getOrDefault(id, new RecurringJobStats(id, null, null, 0L, null)));
      }
    }
    return List.copyOf(ordered);
  }

  /**
   * One grouped pass over {@code jobrunr_jobs} for the outcome timestamps, plus a correlated count
   * of the FAILED rows newer than that job's own last success — which is what "consecutive" means
   * here, and which degenerates to "all of them" when there is no success on record.
   */
  private Map<String, RecurringJobStats> outcomes(List<String> jobIds) {
    Table<?> agg =
        dsl.select(
                JOBRUNR_JOBS.RECURRINGJOBID.as("rid"),
                DSL.max(JOBRUNR_JOBS.UPDATEDAT)
                    .filterWhere(JOBRUNR_JOBS.STATE.eq("SUCCEEDED"))
                    .as("last_success"),
                DSL.max(JOBRUNR_JOBS.UPDATEDAT)
                    .filterWhere(JOBRUNR_JOBS.STATE.eq("FAILED"))
                    .as("last_failure"),
                DSL.min(JOBRUNR_JOBS.SCHEDULEDAT)
                    .filterWhere(JOBRUNR_JOBS.STATE.eq("SCHEDULED"))
                    .as("next_scheduled"))
            .from(JOBRUNR_JOBS)
            .where(JOBRUNR_JOBS.RECURRINGJOBID.in(jobIds))
            .groupBy(JOBRUNR_JOBS.RECURRINGJOBID)
            .asTable("agg");

    Field<String> rid = agg.field("rid", String.class);
    Field<LocalDateTime> lastSuccess = agg.field("last_success", LocalDateTime.class);
    Field<LocalDateTime> lastFailure = agg.field("last_failure", LocalDateTime.class);
    Field<LocalDateTime> nextScheduled = agg.field("next_scheduled", LocalDateTime.class);
    Field<Integer> consecutiveFailures =
        DSL.field(
                DSL.selectCount()
                    .from(JOBRUNR_JOBS)
                    .where(
                        JOBRUNR_JOBS
                            .RECURRINGJOBID
                            .eq(rid)
                            .and(JOBRUNR_JOBS.STATE.eq("FAILED"))
                            .and(lastSuccess.isNull().or(JOBRUNR_JOBS.UPDATEDAT.gt(lastSuccess)))))
            .as("consecutive_failures");

    Map<String, RecurringJobStats> byId = new HashMap<>();
    dsl.select(rid, lastSuccess, lastFailure, nextScheduled, consecutiveFailures)
        .from(agg)
        .fetch()
        .forEach(
            r -> {
              Integer failures = r.get(consecutiveFailures);
              byId.put(
                  r.get(rid),
                  new RecurringJobStats(
                      r.get(rid),
                      utc(r.get(lastSuccess)),
                      utc(r.get(lastFailure)),
                      failures == null ? 0L : failures.longValue(),
                      utc(r.get(nextScheduled))));
            });
    return byId;
  }

  @Override
  public long backgroundJobServerCount() {
    return dsl.fetchCount(JOBRUNR_BACKGROUNDJOBSERVERS);
  }

  private long count(Table<?> table, Condition condition) {
    return dsl.fetchCount(table, condition);
  }

  /**
   * JobRunr stores UTC in a naive {@code TIMESTAMP}. Attach the offset explicitly — never let the
   * JVM default zone decide, or every job timestamp shifts by the server's offset.
   */
  private static Instant utc(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }
}
