package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.JOURNAL_ENTRY;
import static com.loai.inventory.repository.generated.Tables.JOURNAL_LINE;
import static com.loai.inventory.repository.generated.Tables.LEDGER_ACCOUNT;
import static com.loai.inventory.repository.generated.Tables.LEDGER_SKIP;

import com.loai.inventory.domain.model.ledger.AccountStatement;
import com.loai.inventory.domain.model.ledger.AccountStatementLine;
import com.loai.inventory.domain.model.ledger.AccountType;
import com.loai.inventory.domain.model.ledger.JournalEntryView;
import com.loai.inventory.domain.model.ledger.JournalLineView;
import com.loai.inventory.domain.model.ledger.JournalPage;
import com.loai.inventory.domain.model.ledger.LedgerChart;
import com.loai.inventory.domain.model.ledger.LedgerHealth;
import com.loai.inventory.domain.model.ledger.PostingSummary;
import com.loai.inventory.domain.model.ledger.Side;
import com.loai.inventory.domain.model.ledger.TrialBalanceRow;
import com.loai.inventory.domain.repository.LedgerRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Postgres/jOOQ implementation of the general ledger (stories/general_ledger.md).
 *
 * <p><b>The poster.</b> {@link #postMissing} runs one set-based statement per {@link Template}: a
 * {@code src} CTE selects the source rows that imply an entry (an issued invoice, a verified
 * receipt, ...), the ones with no {@code journal_entry} yet are numbered and inserted, and their
 * legs are written from a {@code VALUES} template joined to the org's chart, zero-amount legs
 * dropped. The whole thing is idempotent by the entry's unique source key, so a catch-up on read, a
 * replay after a crash and a full rebuild are the same statement. Each template carries two
 * predicates: {@code base} says which rows are events, {@code guard} says which of those the
 * template can post honestly (an invoice whose totals do not add up is not posted — it is
 * <em>counted</em> by {@link #health} as unposted instead, so a data defect becomes a number on the
 * health screen, never a wrong entry). The deferred trigger {@code journal_line_balanced} (V101)
 * refuses the commit should a template ever produce an unbalanced entry anyway.
 *
 * <p>SQL is plain text with jOOQ {@code {0}} placeholders (the org id, bound once and referenced
 * wherever the statement needs it) — a dozen INSERT…SELECT CTEs read far better as SQL than as DSL,
 * and the reads that benefit from generated types use them.
 */
public final class LedgerRepositoryImpl implements LedgerRepository {

  /** The SQL twin of {@link LedgerChart#cashAccountFor}; the unit test pins the pair. */
  static final String CASH_ACCOUNT_BY_PROVIDER =
      "CASE %s::text WHEN 'cash' THEN '1000' WHEN 'instapay_manual' THEN '1010'"
          + " WHEN 'instapay_in_store' THEN '1010' WHEN 'paymob_card' THEN '1020' END";

  static final String KNOWN_PROVIDERS =
      "('cash', 'instapay_manual', 'instapay_in_store', 'paymob_card')";

  /**
   * One event kind. {@code table} is the source table aliased {@code s}; {@code joins} the extra
   * joins the legs need (may be empty); {@code base} the event predicate over {@code s} alone;
   * {@code guard} the honesty predicate (may be empty; may use the joins); {@code select} the extra
   * columns the legs need beyond {@code sid / posted_at / memo}; {@code legs} the {@code VALUES}
   * rows {@code (seq, side, code, amount)} over the numbered row {@code n}.
   *
   * <p>The poster finds the delta first and only then joins and costs it ({@link #unpostedSql} →
   * {@link #srcSql}): the anti-join over {@code base} alone is a hash anti join on the entry's
   * unique key (2.6 ms for 3 562 invoices on perfdb, against 1 008 ms when the guard sat in the
   * same predicate and the planner estimated 17 rows — tools/seed/results/general_ledger_196.txt),
   * and the per-row cost lookup runs over the handful of unposted rows, not the org's history.
   */
  record Template(
      String kind,
      String event,
      String table,
      String joins,
      String base,
      String guard,
      /**
       * Why a row failing {@code guard} is recorded in {@code ledger_skip}; empty when no guard.
       */
      String skipReason,
      String select,
      String legs) {

    String key() {
      return kind + "/" + event;
    }

    /**
     * The source rows that are events and have neither an entry nor a skip yet — ids only, base
     * predicate only, so the two anti-joins are index-only hash anti joins.
     */
    String unpostedSql(String orgRef) {
      return "SELECT s.id FROM "
          + table
          + " WHERE s.org_id = "
          + orgRef
          + " AND ("
          + base
          + ") AND "
          + notPostedSql(orgRef)
          + " AND "
          + notSkippedSql(orgRef);
    }

    String notSkippedSql(String orgRef) {
      return "NOT EXISTS (SELECT 1 FROM ledger_skip k WHERE k.org_id = "
          + orgRef
          + " AND k.source_type = '"
          + kind
          + "' AND k.event = '"
          + event
          + "' AND k.source_id = s.id::text)";
    }

    /** The delta, joined and guarded, in the shape the legs template reads. */
    String srcSql() {
      return "SELECT s.id::text AS sid, "
          + select
          + " FROM unposted u JOIN "
          + table
          + " ON s.id = u.id "
          + joins
          + (guard.isBlank() ? "" : " WHERE (" + guard + ")");
    }

    String notPostedSql(String orgRef) {
      return "NOT EXISTS (SELECT 1 FROM journal_entry je WHERE je.org_id = "
          + orgRef
          + " AND je.source_type = '"
          + kind
          + "' AND je.event = '"
          + event
          + "' AND je.source_id = s.id::text)";
    }
  }

  private static final String INVOICE_SELECT =
      "s.invoice_number AS memo, s.grand_total, s.subtotal, s.discount_total,"
          + " s.shipping_total, s.tax_total";
  private static final String INVOICE_GUARD =
      "s.grand_total = s.subtotal + s.tax_total + s.shipping_total - s.discount_total";
  private static final String CREDIT_NOTE_SELECT =
      "s.credit_note_number AS memo, s.subtotal, s.tax_total, s.discount_total, s.total";
  private static final String CREDIT_NOTE_GUARD =
      "s.total = s.subtotal + s.tax_total - s.discount_total";

  /**
   * Value-moving stock reasons. RESERVED / RELEASED hold and free units without moving value and
   * are not events here.
   */
  private static final String STOCK_REASONS =
      "('SOLD', 'RETURNED', 'RESTOCKED_FAILED_FULFILLMENT', 'RESTOCK', 'ADJUSTMENT', 'STOCKTAKE')";

  /**
   * What a unit of a stock move cost: the order line's frozen {@code unit_cost} for order-linked
   * moves (so COGS agrees with {@code /reports/profit}), else the row's own V101 stamp.
   */
  private static final String STOCK_JOINS =
      "JOIN product p ON p.id = s.product_id CROSS JOIN LATERAL (SELECT CASE"
          + " WHEN s.reason::text IN ('SOLD', 'RETURNED', 'RESTOCKED_FAILED_FULFILLMENT') THEN"
          + " (SELECT sol.unit_cost FROM sales_order_line sol WHERE sol.sales_order_id ="
          + " s.order_id AND sol.product_id = s.product_id AND sol.unit_cost IS NOT NULL ORDER BY"
          + " sol.id LIMIT 1) ELSE s.unit_cost END AS unit_cost) cost";

  private static final String STOCK_BASE =
      "s.stock_delta <> 0 AND s.reason::text IN " + STOCK_REASONS;

  static final List<Template> TEMPLATES =
      List.of(
          // A sale recognised: the customer owes the gross, the shop earned the net of discounts,
          // shipping is its own revenue line, and the VAT is owed onward.
          new Template(
              "INVOICE",
              "ISSUED",
              "sales_invoice s",
              "",
              "s.issued_at IS NOT NULL AND s.status IN ('ISSUED', 'PAID', 'VOID')",
              INVOICE_GUARD,
              "TOTALS_MISMATCH",
              "s.issued_at AS posted_at, " + INVOICE_SELECT,
              "(1, 'DR', '1100', n.grand_total), (2, 'CR', '4000', n.subtotal),"
                  + " (3, 'DR', '4050', n.discount_total), (4, 'CR', '4100', n.shipping_total),"
                  + " (5, 'CR', '2200', n.tax_total)"),
          // An inert invoice voided (void+reissue): the issue entry stays, this reverses it, dated
          // the void — the accountant's expectation, and what keeps the journal a history.
          new Template(
              "INVOICE",
              "VOIDED",
              "sales_invoice s",
              "",
              "s.issued_at IS NOT NULL AND s.voided_at IS NOT NULL AND s.status = 'VOID'",
              INVOICE_GUARD,
              "TOTALS_MISMATCH",
              "s.voided_at AS posted_at, "
                  + INVOICE_SELECT.replace("AS memo", "|| ' (void)' AS memo"),
              "(1, 'CR', '1100', n.grand_total), (2, 'DR', '4000', n.subtotal),"
                  + " (3, 'CR', '4050', n.discount_total), (4, 'DR', '4100', n.shipping_total),"
                  + " (5, 'DR', '2200', n.tax_total)"),
          // Money arrived at the provider (verified): an asset by rail, owed to the customer until
          // an allocation applies it. Keyed on the transaction, not the payment, so an ORPHAN
          // receipt is on the books from the day it was verified.
          new Template(
              "RECEIPT",
              "RECEIVED",
              "payment_transaction s",
              "",
              "s.direction = 'CREDIT' AND s.verification_status = 'VERIFIED'",
              "s.provider::text IN " + KNOWN_PROVIDERS,
              "UNKNOWN_RAIL",
              "s.occurred_at AS posted_at, s.provider::text || ' ' || s.provider_ref AS memo,"
                  + " s.amount, "
                  + CASH_ACCOUNT_BY_PROVIDER.formatted("s.provider")
                  + " AS cash_code",
              "(1, 'DR', n.cash_code, n.amount), (2, 'CR', '2100', n.amount)"),
          // A payment applied to an invoice: the deposit settles the receivable.
          new Template(
              "ALLOCATION",
              "APPLIED",
              "payment_allocation s",
              "JOIN sales_invoice i ON i.id = s.sales_invoice_id",
              "TRUE",
              "",
              "",
              "s.created_at AS posted_at, i.invoice_number AS memo, s.amount",
              "(1, 'DR', '2100', n.amount), (2, 'CR', '1100', n.amount)"),
          // Something billed is credited back: returns (contra revenue) and the VAT on them come
          // off; the discount share the note carries (V89) reverses the discount taken at issue.
          new Template(
              "CREDIT_NOTE",
              "ISSUED",
              "credit_note s",
              "",
              "s.issued_at IS NOT NULL AND s.status IN ('ISSUED', 'SETTLED', 'VOID')",
              CREDIT_NOTE_GUARD,
              "TOTALS_MISMATCH",
              "s.issued_at AS posted_at, " + CREDIT_NOTE_SELECT,
              "(1, 'DR', '4200', n.subtotal), (2, 'DR', '2200', n.tax_total),"
                  + " (3, 'CR', '4050', n.discount_total), (4, 'CR', '1100', n.total)"),
          // credit_note has no voided_at; updated_at is the void's instant (status flips once).
          new Template(
              "CREDIT_NOTE",
              "VOIDED",
              "credit_note s",
              "",
              "s.issued_at IS NOT NULL AND s.status = 'VOID'",
              CREDIT_NOTE_GUARD,
              "TOTALS_MISMATCH",
              "s.updated_at AS posted_at, "
                  + CREDIT_NOTE_SELECT.replace("AS memo", "|| ' (void)' AS memo"),
              "(1, 'CR', '4200', n.subtotal), (2, 'CR', '2200', n.tax_total),"
                  + " (3, 'DR', '4050', n.discount_total), (4, 'DR', '1100', n.total)"),
          // Money left: a credit-note-backed refund clears the receivable the note created; a
          // direct refund (overpayment, counter change) hands back a deposit never applied.
          new Template(
              "REFUND",
              "EXECUTED",
              "refund s",
              "LEFT JOIN credit_note c ON c.id = s.credit_note_id",
              "s.status = 'EXECUTED' AND s.executed_at IS NOT NULL",
              "s.method::text IN " + KNOWN_PROVIDERS,
              "UNKNOWN_RAIL",
              "s.executed_at AS posted_at, s.method::text || ' refund' || COALESCE(' ' ||"
                  + " c.credit_note_number, '') AS memo, s.amount, CASE WHEN s.credit_note_id IS"
                  + " NOT NULL THEN '1100' ELSE '2100' END AS dr_code, "
                  + CASH_ACCOUNT_BY_PROVIDER.formatted("s.method")
                  + " AS cr_code",
              "(1, 'DR', n.dr_code, n.amount), (2, 'CR', n.cr_code, n.amount)"),
          // Stock value moved: a sale expenses it, a return / failed-fulfillment restock brings it
          // back, a restock adds it against the unbilled supplier, a count or adjustment writes
          // shrinkage (down) or a found gain (up). Uncosted rows are skipped and counted.
          //
          // The template takes abs(delta), so each arm that can go both ways picks its accounts by
          // sign. V102 made RESTOCK one of them — a goods-receipt void writes a negative RESTOCK
          // row, unreachable before it (validateQty refuses qty <= 0), which the fixed arm would
          // have posted as an increase:
          //     RESTOCK, delta > 0 → DR 1200 Inventory / CR 2000 Purchases (unbilled)
          //     RESTOCK, delta < 0 → DR 2000 Purchases / CR 1200 Inventory
          // No posted entry changes (no existing row has a negative RESTOCK delta), so a
          // ?reset=true rebuild over existing data is byte-identical — asserted, not assumed.
          new Template(
              "STOCK",
              "MOVED",
              "inventory_log s",
              STOCK_JOINS,
              STOCK_BASE,
              "cost.unit_cost IS NOT NULL AND cost.unit_cost > 0",
              "UNCOSTED",
              "s.created_at AS posted_at, p.sku || ' ' || s.reason::text || ' ' || s.stock_delta"
                  + " AS memo, abs(s.stock_delta) * cost.unit_cost AS amount, CASE s.reason::text"
                  + " WHEN 'SOLD' THEN '5000' WHEN 'RETURNED' THEN '1200' WHEN"
                  + " 'RESTOCKED_FAILED_FULFILLMENT' THEN '1200' WHEN 'RESTOCK' THEN CASE WHEN"
                  + " s.stock_delta < 0 THEN '2000' ELSE '1200' END ELSE"
                  + " CASE WHEN s.stock_delta < 0 THEN '5100' ELSE '1200' END END AS dr_code,"
                  + " CASE s.reason::text WHEN 'SOLD' THEN '1200' WHEN 'RETURNED' THEN '5000' WHEN"
                  + " 'RESTOCKED_FAILED_FULFILLMENT' THEN '5000' WHEN 'RESTOCK' THEN CASE WHEN"
                  + " s.stock_delta < 0 THEN '1200' ELSE '2000' END ELSE"
                  + " CASE WHEN s.stock_delta < 0 THEN '1200' ELSE '5100' END END AS cr_code",
              "(1, 'DR', n.dr_code, n.amount), (2, 'CR', n.cr_code, n.amount)"),
          // Cash in or out of the drawer outside a sale. The reason is free text, so a pay-out is
          // the owner's drawing until a reason taxonomy exists (stories/general_ledger.md §Known
          // limits); a pay-in is a contribution.
          new Template(
              "CASH_MOVEMENT",
              "RECORDED",
              "cash_movement s",
              "",
              "TRUE",
              "",
              "",
              "s.recorded_at AS posted_at, s.kind::text || ': ' || s.reason AS memo, s.amount,"
                  + " CASE s.kind::text WHEN 'PAY_IN' THEN '1000' ELSE '3100' END AS dr_code,"
                  + " CASE s.kind::text WHEN 'PAY_IN' THEN '3000' ELSE '1000' END AS cr_code",
              "(1, 'DR', n.dr_code, n.amount), (2, 'CR', n.cr_code, n.amount)"),
          // The drawer counted against the ledger: the difference is cash over (a gain) or short
          // (an expense), so the books hold what the drawer holds.
          new Template(
              "CASH_SHIFT",
              "CLOSED",
              "cash_shift s",
              "",
              "s.closed_at IS NOT NULL AND s.counted_cash <> s.expected_cash",
              "",
              "",
              "s.closed_at AS posted_at, 'shift close ' || CASE WHEN s.counted_cash >"
                  + " s.expected_cash THEN 'over' ELSE 'short' END AS memo, abs(s.counted_cash -"
                  + " s.expected_cash) AS amount, CASE WHEN s.counted_cash > s.expected_cash THEN"
                  + " '1000' ELSE '5200' END AS dr_code, CASE WHEN s.counted_cash >"
                  + " s.expected_cash THEN '5200' ELSE '1000' END AS cr_code",
              "(1, 'DR', n.dr_code, n.amount), (2, 'CR', n.cr_code, n.amount)"));

  private static final Logger log = LoggerFactory.getLogger(LedgerRepositoryImpl.class);

  /** Above this a no-op catch-up is logged per kind (stories/general_ledger.md §Measurement). */
  static final long SLOW_CATCH_UP_MS = 500;

  private final DSLContext dsl;

  public LedgerRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  // Chart

  @Override
  public void ensureChart(UUID orgId) {
    var step =
        dsl.insertInto(
            LEDGER_ACCOUNT,
            LEDGER_ACCOUNT.ID,
            LEDGER_ACCOUNT.ORG_ID,
            LEDGER_ACCOUNT.CODE,
            LEDGER_ACCOUNT.NAME,
            LEDGER_ACCOUNT.TYPE,
            LEDGER_ACCOUNT.NORMAL_SIDE);
    for (LedgerChart.Account a : LedgerChart.ACCOUNTS) {
      step =
          step.values(
              UUID.randomUUID(), orgId, a.code(), a.name(), a.type().name(), a.normalSide().name());
    }
    step.onConflict(LEDGER_ACCOUNT.ORG_ID, LEDGER_ACCOUNT.CODE).doNothing().execute();
  }

  // The poster

  @Override
  public PostingSummary postMissing(UUID orgId) {
    // One poster per org at a time: entry_no is MAX+1 inside the statement, and two catch-ups
    // racing on one org would otherwise collide on it (ON CONFLICT would then drop the loser's
    // entries for this run — correct, but wasteful). Transaction-scoped, released at commit.
    dsl.execute("SELECT pg_advisory_xact_lock(hashtext({0}::text))", DSL.val(orgId));
    ensureChart(orgId);
    Map<String, Integer> inserted = new LinkedHashMap<>();
    Map<String, Long> millis = new LinkedHashMap<>();
    long started = System.nanoTime();
    for (Template t : TEMPLATES) {
      long t0 = System.nanoTime();
      inserted.put(t.key(), post(orgId, t));
      millis.put(t.key(), (System.nanoTime() - t0) / 1_000_000);
    }
    long total = (System.nanoTime() - started) / 1_000_000;
    // A catch-up is a few anti-joins over the org's rows (tens of ms on perfdb's largest org); one
    // that takes longer is a plan regression worth a line in the log, per kind.
    if (total > SLOW_CATCH_UP_MS) {
      log.warn("Slow ledger catch-up for org {}: {} ms {}", orgId, total, millis);
    } else if (log.isDebugEnabled()) {
      log.debug("Ledger catch-up for org {}: {} ms {}", orgId, total, millis);
    }
    return new PostingSummary(inserted);
  }

  private int post(UUID orgId, Template t) {
    // `unposted` is MATERIALIZED on purpose: the cheap anti-join runs once over the org's rows
    // and the joins / cost lookups / guard in `src` see only the delta (see Template).
    String sql =
        "WITH acct AS (SELECT code, id FROM ledger_account WHERE org_id = {0}),"
            + " unposted AS MATERIALIZED ("
            + t.unpostedSql("{0}")
            + "),"
            + " src AS ("
            + t.srcSql()
            + "),"
            + " numbered AS (SELECT src.*, gen_random_uuid() AS entry_id, (SELECT"
            + " COALESCE(MAX(entry_no), 0) FROM journal_entry WHERE org_id = {0}) + ROW_NUMBER()"
            + " OVER (ORDER BY src.posted_at, src.sid) AS entry_no FROM src),"
            + " ins AS (INSERT INTO journal_entry (id, org_id, entry_no, posted_at, source_type,"
            + " source_id, event, memo) SELECT entry_id, {0}, entry_no, posted_at, '"
            + t.kind()
            + "', sid, '"
            + t.event()
            + "', memo FROM numbered ON CONFLICT DO NOTHING RETURNING id),"
            + " legs AS (INSERT INTO journal_line (entry_id, org_id, account_id, posted_at, seq,"
            + " side, amount) SELECT n.entry_id, {0}, a.id, n.posted_at, l.seq, l.side, l.amount"
            + " FROM numbered n JOIN ins ON ins.id = n.entry_id CROSS JOIN LATERAL (VALUES "
            + t.legs()
            + ") AS l(seq, side, code, amount) JOIN acct a ON a.code = l.code WHERE l.amount > 0"
            + " RETURNING entry_id)"
            // A delta row the guard refused leaves the delta for good: recorded in ledger_skip
            // under the entry's key shape, counted by health(), cleared by a reset rebuild.
            + (t.skipReason().isBlank()
                ? ""
                : ", skipped AS (INSERT INTO ledger_skip (org_id, source_type, source_id, event,"
                    + " reason) SELECT {0}, '"
                    + t.kind()
                    + "', u.id::text, '"
                    + t.event()
                    + "', '"
                    + t.skipReason()
                    + "' FROM unposted u WHERE NOT EXISTS (SELECT 1 FROM src WHERE src.sid ="
                    + " u.id::text) ON CONFLICT DO NOTHING RETURNING source_id)")
            + " SELECT count(*) FROM ins";
    return dsl.fetchOne(sql, DSL.val(orgId)).get(0, Integer.class);
  }

  @Override
  public int reset(UUID orgId) {
    dsl.execute("SELECT pg_advisory_xact_lock(hashtext({0}::text))", DSL.val(orgId));
    // Lines cascade from the entry; the deferred balance trigger sees no entry and passes. Skips
    // go too: a reset is the one way a row judged unpostable is judged again.
    dsl.deleteFrom(LEDGER_SKIP).where(LEDGER_SKIP.ORG_ID.eq(orgId)).execute();
    return dsl.deleteFrom(JOURNAL_ENTRY).where(JOURNAL_ENTRY.ORG_ID.eq(orgId)).execute();
  }

  // Reads

  /** {@code +amount} for a debit, {@code −amount} for a credit: the DR-positive signed leg. */
  private static Field<BigDecimal> signed() {
    return DSL.when(JOURNAL_LINE.SIDE.eq("DR"), JOURNAL_LINE.AMOUNT)
        .otherwise(JOURNAL_LINE.AMOUNT.neg());
  }

  private static BigDecimal onNormalSide(Side normal, BigDecimal drPositive) {
    return normal == Side.DR ? drPositive : drPositive.negate();
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO.setScale(2) : v.setScale(2);
  }

  @Override
  public List<TrialBalanceRow> trialBalance(UUID orgId, OffsetDateTime from, OffsetDateTime to) {
    Field<BigDecimal> before =
        DSL.coalesce(DSL.sum(DSL.when(JOURNAL_LINE.POSTED_AT.lt(from), signed())), BigDecimal.ZERO)
            .as("before");
    Condition inWindow = JOURNAL_LINE.POSTED_AT.ge(from).and(JOURNAL_LINE.POSTED_AT.lt(to));
    Field<BigDecimal> debit =
        DSL.coalesce(
                DSL.sum(DSL.when(inWindow.and(JOURNAL_LINE.SIDE.eq("DR")), JOURNAL_LINE.AMOUNT)),
                BigDecimal.ZERO)
            .as("debit");
    Field<BigDecimal> credit =
        DSL.coalesce(
                DSL.sum(DSL.when(inWindow.and(JOURNAL_LINE.SIDE.eq("CR")), JOURNAL_LINE.AMOUNT)),
                BigDecimal.ZERO)
            .as("credit");
    // Every chart row appears (LEFT JOINs), zero-filled; lines dated at or after `to` fall out of
    // the join and out of every sum.
    return dsl.select(
            LEDGER_ACCOUNT.CODE,
            LEDGER_ACCOUNT.NAME,
            LEDGER_ACCOUNT.TYPE,
            LEDGER_ACCOUNT.NORMAL_SIDE,
            before,
            debit,
            credit)
        .from(LEDGER_ACCOUNT)
        // One range of journal_line_org_account_posted_idx per account — no join to the entry.
        .leftJoin(JOURNAL_LINE)
        .on(
            JOURNAL_LINE
                .ORG_ID
                .eq(LEDGER_ACCOUNT.ORG_ID)
                .and(JOURNAL_LINE.ACCOUNT_ID.eq(LEDGER_ACCOUNT.ID))
                .and(JOURNAL_LINE.POSTED_AT.lt(to)))
        .where(LEDGER_ACCOUNT.ORG_ID.eq(orgId))
        .groupBy(
            LEDGER_ACCOUNT.CODE,
            LEDGER_ACCOUNT.NAME,
            LEDGER_ACCOUNT.TYPE,
            LEDGER_ACCOUNT.NORMAL_SIDE)
        .orderBy(LEDGER_ACCOUNT.CODE.asc())
        .fetch(
            r -> {
              Side normal = Side.valueOf(r.get(LEDGER_ACCOUNT.NORMAL_SIDE).trim());
              BigDecimal opening = onNormalSide(normal, nz(r.get(before)));
              BigDecimal dr = nz(r.get(debit));
              BigDecimal cr = nz(r.get(credit));
              BigDecimal closing = opening.add(onNormalSide(normal, dr.subtract(cr)));
              return new TrialBalanceRow(
                  r.get(LEDGER_ACCOUNT.CODE),
                  r.get(LEDGER_ACCOUNT.NAME),
                  AccountType.valueOf(r.get(LEDGER_ACCOUNT.TYPE)),
                  normal,
                  opening,
                  dr,
                  cr,
                  closing);
            });
  }

  @Override
  public JournalPage journal(
      UUID orgId,
      OffsetDateTime from,
      OffsetDateTime to,
      String accountCode,
      int offset,
      int limit) {
    Condition where =
        JOURNAL_ENTRY
            .ORG_ID
            .eq(orgId)
            .and(JOURNAL_ENTRY.POSTED_AT.ge(from))
            .and(JOURNAL_ENTRY.POSTED_AT.lt(to));
    if (accountCode != null) {
      where =
          where.and(
              DSL.exists(
                  DSL.selectOne()
                      .from(JOURNAL_LINE)
                      .join(LEDGER_ACCOUNT)
                      .on(LEDGER_ACCOUNT.ID.eq(JOURNAL_LINE.ACCOUNT_ID))
                      .where(
                          JOURNAL_LINE
                              .ENTRY_ID
                              .eq(JOURNAL_ENTRY.ID)
                              .and(LEDGER_ACCOUNT.CODE.eq(accountCode)))));
    }
    long total = dsl.fetchCount(JOURNAL_ENTRY, where);
    List<Record> entries =
        dsl.select(
                JOURNAL_ENTRY.ID,
                JOURNAL_ENTRY.ENTRY_NO,
                JOURNAL_ENTRY.POSTED_AT,
                JOURNAL_ENTRY.SOURCE_TYPE,
                JOURNAL_ENTRY.SOURCE_ID,
                JOURNAL_ENTRY.EVENT,
                JOURNAL_ENTRY.MEMO)
            .from(JOURNAL_ENTRY)
            .where(where)
            .orderBy(JOURNAL_ENTRY.POSTED_AT.asc(), JOURNAL_ENTRY.ENTRY_NO.asc())
            .offset(offset)
            .limit(limit)
            .fetch()
            .map(r -> (Record) r);
    if (entries.isEmpty()) {
      return new JournalPage(List.of(), total);
    }
    List<UUID> ids = entries.stream().map(r -> r.get(JOURNAL_ENTRY.ID)).toList();
    Map<UUID, List<JournalLineView>> lines = new LinkedHashMap<>();
    dsl.select(
            JOURNAL_LINE.ENTRY_ID,
            JOURNAL_LINE.SEQ,
            LEDGER_ACCOUNT.CODE,
            LEDGER_ACCOUNT.NAME,
            JOURNAL_LINE.SIDE,
            JOURNAL_LINE.AMOUNT)
        .from(JOURNAL_LINE)
        .join(LEDGER_ACCOUNT)
        .on(LEDGER_ACCOUNT.ID.eq(JOURNAL_LINE.ACCOUNT_ID))
        .where(JOURNAL_LINE.ENTRY_ID.in(ids))
        .orderBy(JOURNAL_LINE.ENTRY_ID.asc(), JOURNAL_LINE.SEQ.asc())
        .forEach(
            r ->
                lines
                    .computeIfAbsent(r.get(JOURNAL_LINE.ENTRY_ID), k -> new ArrayList<>())
                    .add(
                        new JournalLineView(
                            r.get(JOURNAL_LINE.SEQ),
                            r.get(LEDGER_ACCOUNT.CODE),
                            r.get(LEDGER_ACCOUNT.NAME),
                            Side.valueOf(r.get(JOURNAL_LINE.SIDE).trim()),
                            r.get(JOURNAL_LINE.AMOUNT))));
    List<JournalEntryView> items = new ArrayList<>(entries.size());
    for (Record r : entries) {
      UUID id = r.get(JOURNAL_ENTRY.ID);
      items.add(
          new JournalEntryView(
              id,
              r.get(JOURNAL_ENTRY.ENTRY_NO),
              r.get(JOURNAL_ENTRY.POSTED_AT),
              r.get(JOURNAL_ENTRY.SOURCE_TYPE),
              r.get(JOURNAL_ENTRY.SOURCE_ID),
              r.get(JOURNAL_ENTRY.EVENT),
              r.get(JOURNAL_ENTRY.MEMO),
              lines.getOrDefault(id, List.of())));
    }
    return new JournalPage(items, total);
  }

  @Override
  public Optional<AccountStatement> statement(
      UUID orgId,
      String accountCode,
      OffsetDateTime from,
      OffsetDateTime to,
      int offset,
      int limit) {
    LedgerChart.Account account = LedgerChart.byCode(accountCode);
    if (account == null) {
      return Optional.empty();
    }
    UUID accountId =
        dsl.select(LEDGER_ACCOUNT.ID)
            .from(LEDGER_ACCOUNT)
            .where(LEDGER_ACCOUNT.ORG_ID.eq(orgId).and(LEDGER_ACCOUNT.CODE.eq(accountCode)))
            .fetchOne(LEDGER_ACCOUNT.ID);
    if (accountId == null) {
      // Chart not materialised yet (nothing ever posted): an empty statement, not a 404.
      return Optional.of(new AccountStatement(account, nz(null), List.of(), 0L, nz(null)));
    }
    Condition mine = JOURNAL_LINE.ORG_ID.eq(orgId).and(JOURNAL_LINE.ACCOUNT_ID.eq(accountId));
    BigDecimal beforeDr =
        nz(
            dsl.select(DSL.coalesce(DSL.sum(signed()), BigDecimal.ZERO))
                .from(JOURNAL_LINE)
                .where(mine.and(JOURNAL_LINE.POSTED_AT.lt(from)))
                .fetchOne(0, BigDecimal.class));
    BigDecimal opening = onNormalSide(account.normalSide(), beforeDr);

    Condition inWindow =
        mine.and(JOURNAL_LINE.POSTED_AT.ge(from)).and(JOURNAL_LINE.POSTED_AT.lt(to));
    long total = dsl.selectCount().from(JOURNAL_LINE).where(inWindow).fetchOne(0, Long.class);
    BigDecimal windowDr =
        nz(
            dsl.select(DSL.coalesce(DSL.sum(signed()), BigDecimal.ZERO))
                .from(JOURNAL_LINE)
                .where(inWindow)
                .fetchOne(0, BigDecimal.class));
    BigDecimal closing = opening.add(onNormalSide(account.normalSide(), windowDr));

    // The running balance is a window function over the WHOLE window in posting order, then
    // paged — so page 2 continues page 1's balance instead of restarting from the opening.
    Field<BigDecimal> running =
        DSL.sum(signed())
            .over()
            .orderBy(
                JOURNAL_LINE.POSTED_AT.asc(), JOURNAL_ENTRY.ENTRY_NO.asc(), JOURNAL_LINE.SEQ.asc())
            .rowsUnboundedPreceding()
            .as("running");
    List<AccountStatementLine> items =
        dsl.select(
                JOURNAL_ENTRY.ID,
                JOURNAL_ENTRY.ENTRY_NO,
                JOURNAL_ENTRY.POSTED_AT,
                JOURNAL_ENTRY.SOURCE_TYPE,
                JOURNAL_ENTRY.SOURCE_ID,
                JOURNAL_ENTRY.EVENT,
                JOURNAL_ENTRY.MEMO,
                JOURNAL_LINE.SIDE,
                JOURNAL_LINE.AMOUNT,
                running)
            .from(JOURNAL_LINE)
            .join(JOURNAL_ENTRY)
            .on(JOURNAL_ENTRY.ID.eq(JOURNAL_LINE.ENTRY_ID))
            .where(inWindow)
            .orderBy(
                JOURNAL_LINE.POSTED_AT.asc(), JOURNAL_ENTRY.ENTRY_NO.asc(), JOURNAL_LINE.SEQ.asc())
            .offset(offset)
            .limit(limit)
            .fetch(
                r ->
                    new AccountStatementLine(
                        r.get(JOURNAL_ENTRY.ID),
                        r.get(JOURNAL_ENTRY.ENTRY_NO),
                        r.get(JOURNAL_ENTRY.POSTED_AT),
                        r.get(JOURNAL_ENTRY.SOURCE_TYPE),
                        r.get(JOURNAL_ENTRY.SOURCE_ID),
                        r.get(JOURNAL_ENTRY.EVENT),
                        r.get(JOURNAL_ENTRY.MEMO),
                        Side.valueOf(r.get(JOURNAL_LINE.SIDE).trim()),
                        r.get(JOURNAL_LINE.AMOUNT),
                        opening.add(onNormalSide(account.normalSide(), nz(r.get(running))))));
    return Optional.of(new AccountStatement(account, opening, items, total, closing));
  }

  // Health

  @Override
  public LedgerHealth health(UUID orgId) {
    long posted = dsl.fetchCount(JOURNAL_ENTRY, JOURNAL_ENTRY.ORG_ID.eq(orgId));
    long unbalanced =
        dsl.fetchOne(
                "SELECT count(*) FROM (SELECT e.id FROM journal_entry e LEFT JOIN journal_line l"
                    + " ON l.entry_id = e.id WHERE e.org_id = {0} GROUP BY e.id HAVING"
                    + " COALESCE(SUM(CASE l.side WHEN 'DR' THEN l.amount ELSE -l.amount END), 0)"
                    + " <> 0 OR count(l.id) < 2) u",
                DSL.val(orgId))
            .get(0, Long.class);
    Map<String, Long> unposted = new LinkedHashMap<>();
    for (Template t : TEMPLATES) {
      String sql = "SELECT count(*) FROM (" + t.unpostedSql("{0}") + ") u";
      unposted.put(t.key(), dsl.fetchOne(sql, DSL.val(orgId)).get(0, Long.class));
    }
    // Coverage, from the skip table the poster maintains: per "kind:reason" — and the one the
    // reports already speak of, uncosted stock moves, pulled out by name.
    Map<String, Long> skipped = new LinkedHashMap<>();
    dsl.select(LEDGER_SKIP.SOURCE_TYPE, LEDGER_SKIP.EVENT, LEDGER_SKIP.REASON, DSL.count())
        .from(LEDGER_SKIP)
        .where(LEDGER_SKIP.ORG_ID.eq(orgId))
        .groupBy(LEDGER_SKIP.SOURCE_TYPE, LEDGER_SKIP.EVENT, LEDGER_SKIP.REASON)
        .orderBy(LEDGER_SKIP.SOURCE_TYPE, LEDGER_SKIP.EVENT, LEDGER_SKIP.REASON)
        .forEach(
            r ->
                skipped.put(
                    r.value1() + "/" + r.value2() + ":" + r.value3(), r.value4().longValue()));
    long uncosted = skipped.getOrDefault("STOCK/MOVED:UNCOSTED", 0L);

    BigDecimal arLedger = balance(orgId, LedgerChart.ACCOUNTS_RECEIVABLE, Side.DR);
    BigDecimal arSource =
        nz(
            dsl.fetchOne(
                    "SELECT COALESCE((SELECT SUM(grand_total - paid_amount) FROM sales_invoice"
                        + " WHERE org_id = {0} AND status IN ('ISSUED', 'PAID')), 0) -"
                        + " COALESCE((SELECT SUM(total) FROM credit_note WHERE org_id = {0} AND"
                        + " status IN ('ISSUED', 'SETTLED')), 0) + COALESCE((SELECT SUM(amount)"
                        + " FROM refund WHERE org_id = {0} AND status = 'EXECUTED' AND"
                        + " credit_note_id IS NOT NULL), 0)",
                    DSL.val(orgId))
                .get(0, BigDecimal.class));
    BigDecimal depositsLedger = balance(orgId, LedgerChart.CUSTOMER_DEPOSITS, Side.CR);
    BigDecimal depositsSource =
        nz(
            dsl.fetchOne(
                    "SELECT COALESCE((SELECT SUM(unallocated_amount) FROM payment WHERE org_id ="
                        + " {0}), 0) + COALESCE((SELECT SUM(t.amount) FROM payment_transaction t"
                        + " WHERE t.org_id = {0} AND t.direction = 'CREDIT' AND"
                        + " t.verification_status = 'VERIFIED' AND NOT EXISTS (SELECT 1 FROM"
                        + " payment p WHERE p.payment_transaction_id = t.id)), 0)",
                    DSL.val(orgId))
                .get(0, BigDecimal.class));
    OffsetDateTime lastPostedAt =
        dsl.select(DSL.max(JOURNAL_ENTRY.CREATED_AT))
            .from(JOURNAL_ENTRY)
            .where(JOURNAL_ENTRY.ORG_ID.eq(orgId))
            .fetchOne(0, OffsetDateTime.class);
    return new LedgerHealth(
        posted,
        unbalanced,
        unposted,
        skipped,
        uncosted,
        List.of(
            new LedgerHealth.Check("accounts_receivable", arLedger, arSource),
            new LedgerHealth.Check("customer_deposits", depositsLedger, depositsSource)),
        lastPostedAt);
  }

  /** An account's all-time balance on {@code normal}. */
  private BigDecimal balance(UUID orgId, String code, Side normal) {
    BigDecimal dr =
        nz(
            dsl.select(DSL.coalesce(DSL.sum(signed()), BigDecimal.ZERO))
                .from(JOURNAL_LINE)
                .join(LEDGER_ACCOUNT)
                .on(LEDGER_ACCOUNT.ID.eq(JOURNAL_LINE.ACCOUNT_ID))
                .where(JOURNAL_LINE.ORG_ID.eq(orgId).and(LEDGER_ACCOUNT.CODE.eq(code)))
                .fetchOne(0, BigDecimal.class));
    return onNormalSide(normal, dr);
  }
}
