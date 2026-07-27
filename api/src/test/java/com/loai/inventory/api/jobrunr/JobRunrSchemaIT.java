package com.loai.inventory.api.jobrunr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.jobrunr.storage.JobStats;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.storage.StorageProviderUtils.DatabaseOptions;
import org.jobrunr.storage.sql.common.SqlStorageProviderFactory;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The regression pin for the vendored JobRunr storage schema ({@code
 * stories/jobrunr_schema_drift.md}).
 *
 * <p>We run JobRunr with {@link DatabaseOptions#SKIP_CREATE} and own its DDL under Flyway (V29 +
 * V74), which buys reproducibility and costs us the library's own self-migration. The bill came due
 * when {@code 0d22001} bumped the pom from 7.2.2 to 8.7.1 without re-vendoring: {@code
 * jobrunr_jobs_stats} kept the 7.x shape while {@code JobStatsView} started reading an {@code
 * awaiting} column that no longer existed. Nothing caught it, because {@code SKIP_CREATE} validates
 * that the tables <em>exist</em>, never that they <em>match</em>.
 *
 * <p>The tempting guard — {@code DatabaseCreator.validateTables()} at boot — would not have caught
 * it either: it is {@code getAllTableNames()} → {@code removeAll(expected)} → {@code isEmpty()}, a
 * table-<em>name</em> set comparison with no column inspection at all. That is the same existence
 * check this drift walked straight past, so a startup guard built on it would be a green light that
 * means nothing.
 *
 * <p>So pin the call that actually throws instead. Two tests, deliberately overlapping:
 *
 * <ul>
 *   <li>{@link #getJobStats_returnsAgainstTheMigratedSchema()} fails when the <em>library</em>
 *       starts reading a column we have not vendored.
 *   <li>{@link #jobStatsView_carriesEveryColumnJobRunr8Reads()} fails when the <em>schema</em>
 *       loses one.
 * </ul>
 *
 * <p>Being told which of the two broke is the whole diagnostic value; a single test would leave the
 * next person guessing whether to re-vendor the DDL or roll back the dependency.
 *
 * <p>Note on severity, so nobody over-reads this file: nothing in the running application registers
 * a {@code JobStatsChangeListener} (those come only from the dashboard, the JMX extension or the
 * micrometer binder, and {@code AppConfig} enables none of them), so {@code getJobStats()} is never
 * called in production today. This was an armed trap, not a live outage — it would have fired the
 * first time an operator enabled the dashboard, which is exactly when they are already debugging
 * something else.
 */
@Testcontainers
class JobRunrSchemaIT {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;

  @BeforeAll
  static void startInfra() {
    // The container runs our real Flyway locations, so the schema under test is the schema we ship
    // — not a hand-built fixture that could be corrected into passing.
    Flyway.configure()
        .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
        .schemas("inventorydb")
        .locations("classpath:db/migration")
        .load()
        .migrate();

    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(PG.getJdbcUrl());
    cfg.setUsername(PG.getUsername());
    cfg.setPassword(PG.getPassword());
    cfg.setMaximumPoolSize(4);
    cfg.setConnectionInitSql("SET search_path TO inventorydb");
    dataSource = new HikariDataSource(cfg);
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  /**
   * The pin. Builds the same {@link StorageProvider} {@code AppConfig} builds — same factory, same
   * {@code SKIP_CREATE} — and makes the one call that reads {@code jobrunr_jobs_stats}.
   *
   * <p>Before V74 this throws: {@code JobStatsView} issues {@code SELECT * FROM jobrunr_jobs_stats}
   * and then asks the result set for {@code awaiting}, which V29's 7.2.2 view does not have.
   * Asserting on {@link JobStats#getAwaiting()} rather than merely on "it returned" is what stops
   * this test passing against the old view should the library ever start tolerating the absence.
   */
  @Test
  void getJobStats_returnsAgainstTheMigratedSchema() {
    try (StorageProvider storage =
        SqlStorageProviderFactory.using(dataSource, null, DatabaseOptions.SKIP_CREATE)) {
      JobStats stats = storage.getJobStats();

      assertNotNull(stats, "getJobStats() must return against the migrated schema");
      // An empty jobrunr_jobs is the honest state here — no background server runs in this test —
      // so every bucket is zero. The value matters far less than the fact that reading it worked.
      assertEquals(0L, stats.getAwaiting(), "awaiting must be readable, not merely present");
      assertEquals(0L, stats.getTotal());
      assertEquals(0, stats.getBackgroundJobServers());
      assertEquals(0, stats.getRecurringJobs());
    }
  }

  /**
   * The other half. Reads the view's column set straight from {@code information_schema} and
   * asserts on the two columns v016 added.
   *
   * <p>{@code processed} is asserted even though 8.7.1's {@code JobStatsView} does not read it —
   * only {@code awaiting} is. It is in the view because JobRunr's own migration put it there, and
   * vendoring the file verbatim means we carry it; a later version that starts reading it must not
   * be the moment we discover we trimmed it.
   */
  @Test
  void jobStatsView_carriesEveryColumnJobRunr8Reads() {
    Set<String> columns =
        Set.copyOf(
            dsl.fetch(
                    "select column_name from information_schema.columns"
                        + " where table_schema = 'inventorydb' and table_name = 'jobrunr_jobs_stats'")
                .getValues("column_name", String.class));

    assertTrue(columns.contains("awaiting"), "v016 column `awaiting` missing; columns=" + columns);
    assertTrue(
        columns.contains("processed"), "v016 column `processed` missing; columns=" + columns);
    // The pre-v016 columns must survive the rewrite — v016 is a DROP VIEW + CREATE VIEW, not an
    // ALTER, so a mis-vendored file could just as easily lose one as add two.
    assertTrue(
        columns.containsAll(
            Set.of(
                "total",
                "scheduled",
                "enqueued",
                "processing",
                "failed",
                "succeeded",
                "alltimesucceeded",
                "deleted",
                "nbrofbackgroundjobservers",
                "nbrofrecurringjobs")),
        "a pre-v016 column was lost in the rewrite; columns=" + columns);
  }
}
