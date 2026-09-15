package com.loai.inventory.api.ledger;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.loai.inventory.repository.LedgerRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.service.LedgerService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

/**
 * NOT a test — the perfdb measurement behind {@code tools/seed/results/general_ledger_196.txt}.
 * Runs only when {@code PERFDB_BENCH=1}; against the seeded benchmark database on the compose
 * Postgres. Prints timings (warm, second of two kept) and the plans of the two reads every ledger
 * page pays for: the no-op catch-up's costliest anti-join and the trial balance.
 */
class PerfdbLedgerBench {

  @Test
  void bench() {
    assumeTrue("1".equals(System.getenv("PERFDB_BENCH")), "PERFDB_BENCH=1 to run");
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl("jdbc:postgresql://localhost:5433/perfdb");
    cfg.setUsername("postgres");
    cfg.setPassword("postgres");
    cfg.setMaximumPoolSize(4);
    cfg.setConnectionInitSql("SET search_path TO inventorydb");
    try (HikariDataSource ds = new HikariDataSource(cfg)) {
      DSLContext dsl = DSL.using(ds, SQLDialect.POSTGRES);
      LedgerService ledger =
          new LedgerService(dsl, new LedgerRepositoryFactoryImpl(), new OrgRepositoryFactoryImpl());

      UUID org =
          dsl.fetchOne(
                  "select org_id from sales_invoice group by org_id order by count(*) desc limit 1")
              .get(0, UUID.class);
      System.out.println("== perfdb largest org by invoices: " + org);
      System.out.println(
          "   rows: "
              + dsl.fetchOne(
                  "select (select count(*) from sales_invoice where org_id = {0}) inv,"
                      + " (select count(*) from payment_transaction where org_id = {0}) txn,"
                      + " (select count(*) from payment_allocation where org_id = {0}) alloc,"
                      + " (select count(*) from credit_note where org_id = {0}) cn,"
                      + " (select count(*) from refund where org_id = {0}) rf,"
                      + " (select count(*) from inventory_log where org_id = {0}) log",
                  DSL.val(org)));

      time("rebuild reset=true (full backfill of the org)", () -> ledger.rebuild(org, true));
      // Steady state: autovacuum sets the visibility map within minutes of a backfill, and the
      // JDBC driver switches to server-side prepared statements after five executions.
      dsl.execute("VACUUM ANALYZE journal_entry, journal_line, ledger_skip");
      for (int i = 0; i < 7; i++) {
        ledger.catchUp(org);
      }
      time("catch-up, nothing to post (8th, kept)", () -> ledger.catchUp(org));
      System.out.println("   posted entries: " + ledger.health(org).postedEntries());
      String to = OffsetDateTime.now(ZoneOffset.UTC).toString();
      String from30 = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30).toString();
      String from366 = OffsetDateTime.now(ZoneOffset.UTC).minusDays(366).toString();
      time("trial balance 30d (1st)", () -> ledger.trialBalance(org, from30, to));
      time("trial balance 30d (2nd, kept)", () -> ledger.trialBalance(org, from30, to));
      time(
          "trial balance 366d (2nd, kept)",
          () -> {
            ledger.trialBalance(org, from366, to);
            return ledger.trialBalance(org, from366, to);
          });
      time(
          "journal page 50, 366d (2nd, kept)",
          () -> {
            ledger.journal(org, from366, to, null, "0", "50");
            return ledger.journal(org, from366, to, null, "0", "50");
          });
      time(
          "statement 1100 page 50, 366d (2nd, kept)",
          () -> {
            ledger.statement(org, "1100", from366, to, "0", "50");
            return ledger.statement(org, "1100", from366, to, "0", "50");
          });
      time(
          "health (2nd, kept)",
          () -> {
            ledger.health(org);
            return ledger.health(org);
          });

      System.out.println("\n== EXPLAIN: no-op catch-up, the STOCK unposted anti-join (base only)");
      explain(
          dsl,
          "SELECT s.id FROM inventory_log s WHERE s.org_id = '"
              + org
              + "' AND (s.stock_delta <> 0 AND s.reason::text IN ('SOLD', 'RETURNED',"
              + " 'RESTOCKED_FAILED_FULFILLMENT', 'RESTOCK', 'ADJUSTMENT', 'STOCKTAKE')) AND NOT"
              + " EXISTS (SELECT 1 FROM journal_entry je WHERE je.org_id = '"
              + org
              + "' AND je.source_type = 'STOCK' AND je.event = 'MOVED' AND je.source_id ="
              + " s.id::text)");
      System.out.println("\n== EXPLAIN: no-op catch-up, the INVOICE/ISSUED unposted anti-join");
      explain(
          dsl,
          "SELECT s.id FROM sales_invoice s WHERE s.org_id = '"
              + org
              + "' AND (s.issued_at IS NOT NULL AND s.status IN ('ISSUED', 'PAID', 'VOID')) AND"
              + " NOT EXISTS (SELECT 1 FROM journal_entry je WHERE je.org_id = '"
              + org
              + "' AND je.source_type = 'INVOICE' AND je.event = 'ISSUED' AND je.source_id ="
              + " s.id::text)");
      System.out.println(
          "\n== EXPLAIN: no-op catch-up, the ALLOCATION unposted anti-join (V101 index)");
      explain(
          dsl,
          "SELECT s.id FROM payment_allocation s WHERE s.org_id = '"
              + org
              + "' AND NOT EXISTS (SELECT 1 FROM journal_entry je WHERE je.org_id = '"
              + org
              + "' AND je.source_type = 'ALLOCATION' AND je.event = 'APPLIED' AND je.source_id ="
              + " s.id::text)");
      System.out.println("\n== EXPLAIN: trial balance 366d (the jOOQ query's shape)");
      explain(
          dsl,
          "SELECT a.code, coalesce(sum(case when e.posted_at < '"
              + from366
              + "' then case l.side when 'DR' then l.amount else -l.amount end end), 0),"
              + " coalesce(sum(case when e.posted_at >= '"
              + from366
              + "' and e.posted_at < '"
              + to
              + "' and l.side = 'DR' then l.amount end), 0), coalesce(sum(case when e.posted_at"
              + " >= '"
              + from366
              + "' and e.posted_at < '"
              + to
              + "' and l.side = 'CR' then l.amount end), 0) FROM ledger_account a LEFT JOIN"
              + " journal_line l ON l.account_id = a.id LEFT JOIN journal_entry e ON e.id ="
              + " l.entry_id AND e.posted_at < '"
              + to
              + "' WHERE a.org_id = '"
              + org
              + "' GROUP BY a.code ORDER BY a.code");

      System.out.println("\n== all orgs: sweepAll (the job's first tick = the whole backfill)");
      time("sweepAll(100) first tick", () -> ledger.sweepAll(100));
      time("sweepAll(100) second tick, nothing to post", () -> ledger.sweepAll(100));
      System.out.println(
          "   journal totals: "
              + dsl.fetchOne(
                  "select (select count(*) from journal_entry) entries, (select count(*) from"
                      + " journal_line) lines, pg_size_pretty(pg_total_relation_size('journal_entry')"
                      + " + pg_total_relation_size('journal_line')) size"));
    }
  }

  private static void explain(DSLContext dsl, String sql) {
    dsl.fetch("EXPLAIN (ANALYZE, BUFFERS) " + sql); // warm
    dsl.fetch("EXPLAIN (ANALYZE, BUFFERS) " + sql)
        .forEach(r -> System.out.println("   " + r.get(0)));
  }

  private static void time(String label, Supplier<?> work) {
    long t0 = System.nanoTime();
    Object result = work.get();
    long ms = (System.nanoTime() - t0) / 1_000_000;
    System.out.println(
        "   " + label + ": " + ms + " ms" + (result == null ? "" : " → " + brief(result)));
  }

  private static String brief(Object o) {
    String s = String.valueOf(o);
    return s.length() > 160 ? s.substring(0, 160) + "…" : s;
  }
}
