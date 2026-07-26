package com.loai.inventory.api.admin;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.JOBRUNR_JOBS;
import static com.loai.inventory.repository.generated.Tables.JOBRUNR_RECURRING_JOBS;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.REFUND;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.servlet.handler.OverviewAdminHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.domain.repository.PaymentTransactionRepository;
import com.loai.inventory.repository.PaymentRepositoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryImpl;
import com.loai.inventory.repository.PlatformStatsRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryImpl;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.PaymentDirection;
import com.loai.inventory.repository.generated.enums.PaymentProvider;
import com.loai.inventory.repository.generated.enums.PaymentReconciliationStatus;
import com.loai.inventory.repository.generated.enums.PaymentStatus;
import com.loai.inventory.repository.generated.enums.PaymentVerificationStatus;
import com.loai.inventory.repository.generated.enums.RefundStatus;
import com.loai.inventory.service.platform.PlatformOverviewService;
import com.loai.inventory.service.platform.PlatformOverviewService.JobConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration coverage for {@code GET /api/admin/overview} ({@code stories/platform_overview.md}).
 *
 * <p>The whole slice exists because no read in the org plane can answer a question about the
 * platform, so the first test is the one that proves the exception works: a queue count spanning
 * two tenants. The second is the pin that keeps it honest — every platform count equals the sum of
 * the org-scoped counts it will drill into, because both sides run the same predicate.
 *
 * <p>The handler is driven directly (rather than through Tomcat) so the authz matrix and the
 * 405/404 contract are exercised against the real service and a real database.
 */
@Testcontainers
class PlatformOverviewIT {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  private static final String SECURITY_CONTEXT_ATTR = "securityContext";
  private static final String SWEEPER = "order-ttl-sweeper";
  private static final String NOTIFY_SWEEPER = "notification-delivery-sweeper";
  private static final String PURGE = "unverified-account-purge";
  private static final List<JobConfig> JOB_CONFIGS =
      List.of(
          new JobConfig(SWEEPER, Duration.ofSeconds(30)),
          new JobConfig(NOTIFY_SWEEPER, Duration.ofSeconds(10)),
          new JobConfig(PURGE, Duration.ofDays(1)));

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ObjectMapper mapper;

  // Org-scoped repositories — the "other side" of the count-parity assertions.
  static RefundRepositoryImpl refundRepo;
  static PaymentRepositoryImpl paymentRepo;
  static PaymentTransactionRepositoryImpl transactionRepo;
  static SalesOrderRepositoryImpl salesOrderRepo;

  private final AtomicInteger seq = new AtomicInteger(1);

  @BeforeAll
  static void startInfra() {
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
    cfg.setMaximumPoolSize(8);
    cfg.setConnectionInitSql("SET search_path TO inventorydb");
    dataSource = new HikariDataSource(cfg);
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    mapper = ObjectMapperProvider.build();

    refundRepo = new RefundRepositoryImpl(dsl);
    paymentRepo = new PaymentRepositoryImpl(dsl);
    transactionRepo = new PaymentTransactionRepositoryImpl(dsl);
    salesOrderRepo = new SalesOrderRepositoryImpl(dsl);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void freshSchema() {
    dsl.execute(
        "TRUNCATE refund, payment, payment_transaction, sales_order, notification_delivery,"
            + " notification, customer, org RESTART IDENTITY CASCADE");
    dsl.execute("TRUNCATE jobrunr_jobs, jobrunr_recurring_jobs");
  }

  // ── The point of the slice ────────────────────────────────────────────────

  /**
   * The headline: one pending refund in each of two orgs is <em>two</em> pending refunds on the
   * platform. Nothing in the org plane can produce this number, which is why the tenancy exception
   * exists.
   */
  @Test
  void overview_countsEveryQueueAcrossOrgs() {
    UUID acme = createOrg("acme");
    UUID beta = createOrg("beta");
    createRefund(acme, RefundStatus.PENDING);
    createRefund(beta, RefundStatus.PENDING);
    createRefund(beta, RefundStatus.EXECUTED); // a settled refund is not a backlog

    JsonNode body = get(platform(SystemRole.ADMIN), 200);

    assertEquals(2, body.path("queues").path("pending_refunds").asLong());
    // …and the per-org reads still see only their own row, so the exception did not leak downward.
    assertEquals(
        1, refundRepo.count(acme, com.loai.inventory.domain.model.RefundStatus.PENDING, null));
    assertEquals(
        1, refundRepo.count(beta, com.loai.inventory.domain.model.RefundStatus.PENDING, null));
  }

  /**
   * The pin against a tile and its drill-down ever disagreeing: for every queue with an org-scoped
   * list, Σ per-org total == the platform count.
   *
   * <p>Two queues have no org-scoped list to sum. {@code expired_pending_orders} is checked against
   * the sweeper's own cross-org candidate query instead — the strongest available equivalent, since
   * that query <em>is</em> the definition of an expired order. {@code failed_emails} has no org
   * dimension at all ({@code notification_delivery} joins to {@code notification} for its org), so
   * it is checked against a direct per-org count through that join.
   */
  @Test
  void overview_queueCountsMatchTheOrgScopedLists() {
    UUID acme = createOrg("acme");
    UUID beta = createOrg("beta");

    createRefund(acme, RefundStatus.PENDING);
    createRefund(beta, RefundStatus.PENDING);
    createRefund(beta, RefundStatus.PENDING);
    createRefund(acme, RefundStatus.CANCELLED);

    createPayment(acme, PaymentStatus.DISPUTED);
    createPayment(beta, PaymentStatus.DISPUTED);
    createPayment(acme, PaymentStatus.ALLOCATED);

    createOrphanTransaction(acme);
    createOrphanTransaction(beta);
    createOrphanTransaction(beta);
    createResolvedOrphanTransaction(acme); // has a payment → out of the queue

    createExpiredPendingOrder(acme);
    createExpiredPendingOrder(beta);
    createLivePendingOrder(acme); // not yet expired

    createFailedEmail(acme);
    createFailedEmail(beta);
    createFailedEmail(beta);
    createSentEmail(acme);

    JsonNode queues = get(platform(SystemRole.ADMIN), 200).path("queues");

    assertEquals(
        refundRepo.count(acme, com.loai.inventory.domain.model.RefundStatus.PENDING, null)
            + refundRepo.count(beta, com.loai.inventory.domain.model.RefundStatus.PENDING, null),
        queues.path("pending_refunds").asLong());

    assertEquals(
        paymentRepo.count(acme, com.loai.inventory.domain.model.PaymentStatus.DISPUTED, false)
            + paymentRepo.count(
                beta, com.loai.inventory.domain.model.PaymentStatus.DISPUTED, false),
        queues.path("open_disputes").asLong());

    assertEquals(
        transactionRepo.count(acme, openOrphanFilter())
            + transactionRepo.count(beta, openOrphanFilter()),
        queues.path("orphan_transactions").asLong());

    // The sweeper's own candidate set — the operational definition of "expired".
    assertEquals(
        salesOrderRepo.findExpiredPendingIds(100).size(),
        queues.path("expired_pending_orders").asLong());

    assertEquals(
        failedEmailsForOrg(acme) + failedEmailsForOrg(beta), queues.path("failed_emails").asLong());

    // Sanity: the numbers are the ones seeded, not all-zero agreement.
    assertEquals(3, queues.path("pending_refunds").asLong());
    assertEquals(2, queues.path("open_disputes").asLong());
    assertEquals(3, queues.path("orphan_transactions").asLong());
    assertEquals(2, queues.path("expired_pending_orders").asLong());
    assertEquals(3, queues.path("failed_emails").asLong());
  }

  /**
   * The tenant census, including the suspended split the org list's {@code ?status=} filter uses.
   */
  @Test
  void overview_countsTenantsByStatus() {
    createOrg("acme");
    createOrg("beta");
    UUID suspended = createOrg("gamma");
    dsl.update(ORG).set(ORG.ACTIVE, false).where(ORG.ID.eq(suspended)).execute();
    UUID old = createOrg("delta");
    dsl.update(ORG)
        .set(ORG.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC).minusDays(30))
        .where(ORG.ID.eq(old))
        .execute();

    JsonNode tenants = get(platform(SystemRole.ADMIN), 200).path("tenants");

    assertEquals(4, tenants.path("total").asLong());
    assertEquals(3, tenants.path("active").asLong());
    assertEquals(1, tenants.path("suspended").asLong());
    assertEquals(3, tenants.path("provisioned_last_7d").asLong());
  }

  // ── Job health ────────────────────────────────────────────────────────────

  /**
   * With the background flag off there are no jobs at all — an explicit, calm "background jobs are
   * off", never three dead ones. Every CI and test environment lands here, so getting it wrong
   * would teach operators to ignore the colour.
   */
  @Test
  void overview_reportsJobsDisabledRatherThanThreeDeadJobs() {
    JsonNode jobs = get(handler(false), platform(SystemRole.ADMIN), 200, "").path("jobs");

    assertFalse(jobs.path("enabled").asBoolean());
    assertTrue(jobs.path("recurring").isArray());
    assertEquals(0, jobs.path("recurring").size());
    assertEquals(0, jobs.path("servers").asLong());
  }

  /**
   * Reading real JobRunr rows: the timestamps come back UTC-correct off a naive {@code TIMESTAMP}
   * column, and the classification distinguishes a healthy job from a failing one and from one with
   * no success on record.
   */
  @Test
  void overview_readsJobOutcomesOffJobRunrTables() {
    registerRecurringJob(SWEEPER);
    registerRecurringJob(NOTIFY_SWEEPER);
    registerRecurringJob(PURGE);

    Instant now = Instant.now();
    // Healthy: a success 5 seconds ago, and an older failure that predates it.
    insertJobRun(SWEEPER, "SUCCEEDED", now.minusSeconds(5));
    insertJobRun(SWEEPER, "FAILED", now.minusSeconds(300));
    // Failing: two failures newer than the last success.
    insertJobRun(NOTIFY_SWEEPER, "SUCCEEDED", now.minusSeconds(90));
    insertJobRun(NOTIFY_SWEEPER, "FAILED", now.minusSeconds(20));
    insertJobRun(NOTIFY_SWEEPER, "FAILED", now.minusSeconds(10));
    // Registered, nothing to judge on — JobRunr has already reaped any success it once had.
    insertJobRun(PURGE, "SCHEDULED", now.plusSeconds(600));

    JsonNode jobs = get(platform(SystemRole.ADMIN), 200).path("jobs");
    assertTrue(jobs.path("enabled").asBoolean());
    Map<String, JsonNode> byId = jobsById(jobs);

    assertEquals(3, byId.size());
    assertEquals("HEALTHY", byId.get(SWEEPER).path("state").asText());
    assertEquals(0, byId.get(SWEEPER).path("consecutive_failures").asLong());
    // The older failure is still reported as a fact — it just does not make the job unhealthy.
    assertTrue(byId.get(SWEEPER).hasNonNull("last_failure_at"));

    assertEquals("FAILING", byId.get(NOTIFY_SWEEPER).path("state").asText());
    assertEquals(2, byId.get(NOTIFY_SWEEPER).path("consecutive_failures").asLong());

    // Not HEALTHY, and the key is absent rather than a fabricated timestamp.
    assertEquals("UNKNOWN", byId.get(PURGE).path("state").asText());
    assertFalse(byId.get(PURGE).has("last_success_at"));
    assertTrue(byId.get(PURGE).hasNonNull("next_scheduled_at"));

    // The UTC round-trip: a success 5s ago must read as ~5s ago, not shifted by the server's zone.
    Instant lastSuccess = Instant.parse(byId.get(SWEEPER).path("last_success_at").asText());
    long driftSeconds = Math.abs(Duration.between(now.minusSeconds(5), lastSuccess).toSeconds());
    assertTrue(driftSeconds < 60, "last_success_at drifted by " + driftSeconds + "s — zone bug");
  }

  /** A job we schedule in code but that JobRunr has no row for is absent, never invented. */
  @Test
  void overview_omitsAnUnregisteredJobRatherThanInventingOne() {
    registerRecurringJob(SWEEPER);
    insertJobRun(SWEEPER, "SUCCEEDED", Instant.now().minusSeconds(5));

    JsonNode jobs = get(platform(SystemRole.ADMIN), 200).path("jobs");
    Map<String, JsonNode> byId = jobsById(jobs);

    assertEquals(1, byId.size());
    assertTrue(byId.containsKey(SWEEPER));
  }

  // ── Shape, freshness, build ───────────────────────────────────────────────

  /**
   * A healthy read carries every section, one {@code as_of}, and no {@code degraded} key at all.
   */
  @Test
  void overview_omitsDegradedWhenEverythingResolved() {
    createOrg("acme");

    JsonNode body = get(platform(SystemRole.ADMIN), 200);

    assertTrue(body.hasNonNull("as_of"));
    assertNotNull(Instant.parse(body.path("as_of").asText()));
    assertTrue(body.hasNonNull("tenants"));
    assertTrue(body.hasNonNull("queues"));
    assertTrue(body.hasNonNull("jobs"));
    assertTrue(body.hasNonNull("build"));
    // Omitted entirely, so a client branches on presence rather than on an empty array.
    assertFalse(body.has("degraded"));
    // No version field by design; commit is absent when BUILD_COMMIT is unset.
    assertFalse(body.path("build").has("version"));
    assertTrue(body.path("build").hasNonNull("started_at"));
  }

  /** An unset {@code BUILD_COMMIT} omits the key — the UI says "dev" rather than guessing. */
  @Test
  void overview_omitsCommitWhenUnset_andCarriesItWhenSet() {
    assertFalse(get(platform(SystemRole.ADMIN), 200).path("build").has("commit"));

    OverviewAdminHandler withCommit =
        new OverviewAdminHandler(
            new PlatformOverviewService(
                dsl,
                new PlatformStatsRepositoryFactoryImpl(),
                true,
                JOB_CONFIGS,
                "9f3c1ab",
                Instant.now()),
            mapper);
    JsonNode build = get(withCommit, platform(SystemRole.ADMIN), 200, "").path("build");
    assertEquals("9f3c1ab", build.path("commit").asText());
  }

  // ── Authz + handler contract ──────────────────────────────────────────────

  /** The whole resource is a read, so SUPPORT sees it — and sees exactly what ADMIN sees. */
  @Test
  void overview_isReadableBySupport() {
    createOrg("acme");
    createRefund(createOrg("beta"), RefundStatus.PENDING);

    JsonNode support = get(platform(SystemRole.SUPPORT), 200);
    JsonNode admin = get(platform(SystemRole.ADMIN), 200);

    assertEquals(1, support.path("queues").path("pending_refunds").asLong());
    // Byte-identical apart from as_of — nothing on this page is tier-gated.
    assertEquals(admin.path("queues"), support.path("queues"));
    assertEquals(admin.path("tenants"), support.path("tenants"));
  }

  /** An org OWNER holds no platform role, so the platform tier is not theirs to read. */
  @Test
  void overview_rejectsAnOrgOwner() {
    JsonNode body = get(orgOwnerOnly(), 403);
    assertEquals(403, body.path("status").asInt());
  }

  @Test
  void overview_rejectsAnonymous() {
    JsonNode body = get(null, 401);
    assertEquals(401, body.path("status").asInt());
  }

  /** The handler contract every admin resource has: GET only, no subpaths. */
  @Test
  void overview_405sOnPost_and404sOnAnUnknownSubpath() {
    Resp post = invoke(handler(true), "POST", platform(SystemRole.ADMIN), "");
    assertEquals(405, post.status);

    Resp subpath = invoke(handler(true), "GET", platform(SystemRole.ADMIN), "/jobs");
    assertEquals(404, subpath.status);

    // A trailing slash is the resource itself, not a subpath.
    assertEquals(200, invoke(handler(true), "GET", platform(SystemRole.ADMIN), "/").status);
  }

  /** The 404-on-subpath check runs before authz, exactly as {@code AuditAdminHandler} does it. */
  @Test
  void overview_subpath404sEvenForAnOrgOwner() {
    assertEquals(404, invoke(handler(true), "GET", orgOwnerOnly(), "/anything").status);
  }

  /** An empty platform still answers with real zeros — nothing seeded is genuinely nothing owed. */
  @Test
  void overview_onAnEmptyPlatform_isZerosNotNulls() {
    JsonNode body = get(platform(SystemRole.ADMIN), 200);

    assertEquals(0, body.path("tenants").path("total").asLong());
    assertEquals(0, body.path("queues").path("pending_refunds").asLong());
    assertFalse(body.has("degraded"));
    assertNull(body.get("degraded"));
  }

  // ── plumbing ──────────────────────────────────────────────────────────────

  private OverviewAdminHandler handler(boolean jobsEnabled) {
    return new OverviewAdminHandler(
        new PlatformOverviewService(
            dsl,
            new PlatformStatsRepositoryFactoryImpl(),
            jobsEnabled,
            JOB_CONFIGS,
            null,
            Instant.now()),
        mapper);
  }

  private JsonNode get(SecurityContext ctx, int expectedStatus) {
    return get(handler(true), ctx, expectedStatus, "");
  }

  private JsonNode get(
      OverviewAdminHandler handler, SecurityContext ctx, int expectedStatus, String remaining) {
    Resp resp = invoke(handler, "GET", ctx, remaining);
    assertEquals(expectedStatus, resp.status, () -> "body was " + resp.body.toString());
    try {
      return mapper.readTree(resp.body.toByteArray());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private Resp invoke(
      OverviewAdminHandler handler, String method, SecurityContext ctx, String remaining) {
    try {
      Resp resp = new Resp();
      handler.handle(method, reqWith(ctx), resp.mock, remaining);
      return resp;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<String, JsonNode> jobsById(JsonNode jobs) {
    java.util.Map<String, JsonNode> byId = new java.util.LinkedHashMap<>();
    jobs.path("recurring").forEach(job -> byId.put(job.path("id").asText(), job));
    return byId;
  }

  private static PaymentTransactionRepository.ListFilter openOrphanFilter() {
    return new PaymentTransactionRepository.ListFilter(
        null,
        com.loai.inventory.domain.model.PaymentReconciliationStatus.ORPHAN,
        false,
        null,
        null);
  }

  private long failedEmailsForOrg(UUID orgId) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from(NOTIFICATION_DELIVERY)
            .join(NOTIFICATION)
            .on(NOTIFICATION.ID.eq(NOTIFICATION_DELIVERY.NOTIFICATION_ID))
            .where(NOTIFICATION.ORG_ID.eq(orgId).and(NOTIFICATION_DELIVERY.STATUS.eq("FAILED"))));
  }

  // ── seed helpers ──────────────────────────────────────────────────────────

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private UUID createOrg(String slug) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug + "-" + id)
        .execute();
    return id;
  }

  /**
   * A direct (payment-backed) refund. {@code refund_check} demands exactly one of {@code
   * credit_note_id} / {@code payment_id}, so the payment is not decoration — a refund without a
   * source is not a row this schema will hold.
   */
  private void createRefund(UUID orgId, RefundStatus status) {
    dsl.insertInto(REFUND)
        .set(REFUND.ID, UUID.randomUUID())
        .set(REFUND.ORG_ID, orgId)
        .set(REFUND.PAYMENT_ID, createPayment(orgId, PaymentStatus.RECEIVED))
        .set(REFUND.AMOUNT, new BigDecimal("10.00"))
        .set(REFUND.STATUS, status)
        .set(REFUND.METHOD, PaymentProvider.instapay_manual)
        .execute();
  }

  private UUID createTransaction(UUID orgId, PaymentReconciliationStatus reconciliation) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.ID, id)
        .set(PAYMENT_TRANSACTION.ORG_ID, orgId)
        .set(PAYMENT_TRANSACTION.PROVIDER, PaymentProvider.instapay_manual)
        .set(PAYMENT_TRANSACTION.PROVIDER_REF, "IPN-" + seq.getAndIncrement())
        .set(PAYMENT_TRANSACTION.DIRECTION, PaymentDirection.CREDIT)
        .set(PAYMENT_TRANSACTION.AMOUNT, new BigDecimal("100.00"))
        .set(PAYMENT_TRANSACTION.VERIFICATION_STATUS, PaymentVerificationStatus.VERIFIED)
        .set(PAYMENT_TRANSACTION.RECONCILIATION_STATUS, reconciliation)
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, now())
        .execute();
    return id;
  }

  private UUID createPayment(UUID orgId, PaymentStatus status) {
    return attachPayment(
        orgId, createTransaction(orgId, PaymentReconciliationStatus.MATCHED), status);
  }

  private UUID attachPayment(UUID orgId, UUID transactionId, PaymentStatus status) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PAYMENT)
        .set(PAYMENT.ID, id)
        .set(PAYMENT.ORG_ID, orgId)
        .set(PAYMENT.PAYMENT_TRANSACTION_ID, transactionId)
        .set(PAYMENT.AMOUNT, new BigDecimal("100.00"))
        .set(PAYMENT.UNALLOCATED_AMOUNT, BigDecimal.ZERO)
        .set(PAYMENT.STATUS, status)
        .set(PAYMENT.RECEIVED_AT, now())
        .execute();
    return id;
  }

  /** ORPHAN with no payment row — the open queue. */
  private void createOrphanTransaction(UUID orgId) {
    createTransaction(orgId, PaymentReconciliationStatus.ORPHAN);
  }

  /** ORPHAN that already has its 1:1 payment — resolved, so out of the queue. */
  private void createResolvedOrphanTransaction(UUID orgId) {
    UUID txn = createTransaction(orgId, PaymentReconciliationStatus.ORPHAN);
    attachPayment(orgId, txn, PaymentStatus.RECEIVED);
  }

  private void createExpiredPendingOrder(UUID orgId) {
    createOrder(orgId, OrderStatus.PENDING_PAYMENT, now().minusHours(1));
  }

  private void createLivePendingOrder(UUID orgId) {
    createOrder(orgId, OrderStatus.PENDING_PAYMENT, now().plusHours(1));
  }

  private void createOrder(UUID orgId, OrderStatus status, OffsetDateTime expiresAt) {
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, UUID.randomUUID())
        .set(SALES_ORDER.ORG_ID, orgId)
        .set(SALES_ORDER.ORDER_NUMBER, "SO-" + seq.getAndIncrement())
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, status)
        .set(SALES_ORDER.EXPIRES_AT, expiresAt)
        .execute();
  }

  private void createFailedEmail(UUID orgId) {
    createDelivery(orgId, "FAILED");
  }

  private void createSentEmail(UUID orgId) {
    createDelivery(orgId, "SENT");
  }

  private void createDelivery(UUID orgId, String status) {
    UUID notificationId = UUID.randomUUID();
    dsl.insertInto(NOTIFICATION)
        .set(NOTIFICATION.ID, notificationId)
        .set(NOTIFICATION.ORG_ID, orgId)
        .set(NOTIFICATION.RECIPIENT_TYPE, "CUSTOMER")
        .set(NOTIFICATION.RECIPIENT_CUSTOMER_ID, createCustomer(orgId))
        .set(NOTIFICATION.TYPE, "ORDER_PLACED")
        .set(NOTIFICATION.TITLE, "t")
        .set(NOTIFICATION.BODY, "b")
        .set(NOTIFICATION.STATUS, "DISPATCHED")
        .execute();
    dsl.insertInto(NOTIFICATION_DELIVERY)
        .set(NOTIFICATION_DELIVERY.ID, UUID.randomUUID())
        .set(NOTIFICATION_DELIVERY.NOTIFICATION_ID, notificationId)
        .set(NOTIFICATION_DELIVERY.CHANNEL, "email")
        .set(NOTIFICATION_DELIVERY.STATUS, status)
        .execute();
  }

  private UUID createCustomer(UUID orgId) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, orgId)
        .set(CUSTOMER.EMAIL, "shopper-" + seq.getAndIncrement() + "@acme.test")
        .execute();
    return id;
  }

  private void registerRecurringJob(String jobId) {
    dsl.insertInto(JOBRUNR_RECURRING_JOBS)
        .set(JOBRUNR_RECURRING_JOBS.ID, jobId)
        .set(JOBRUNR_RECURRING_JOBS.VERSION, 1)
        .set(JOBRUNR_RECURRING_JOBS.JOBASJSON, "{}")
        .set(JOBRUNR_RECURRING_JOBS.CREATEDAT, 0L)
        .execute();
  }

  /**
   * Writes a JobRunr job row the way JobRunr itself does: UTC into a naive {@code TIMESTAMP}. If
   * the read side ever forgets that, this test's UTC round-trip assertion is what catches it.
   */
  private void insertJobRun(String jobId, String state, Instant at) {
    LocalDateTime naiveUtc = LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    dsl.insertInto(JOBRUNR_JOBS)
        .set(JOBRUNR_JOBS.ID, UUID.randomUUID().toString())
        .set(JOBRUNR_JOBS.VERSION, 1)
        .set(JOBRUNR_JOBS.JOBASJSON, "{}")
        .set(JOBRUNR_JOBS.JOBSIGNATURE, jobId)
        .set(JOBRUNR_JOBS.STATE, state)
        .set(JOBRUNR_JOBS.CREATEDAT, naiveUtc)
        .set(JOBRUNR_JOBS.UPDATEDAT, naiveUtc)
        .set(JOBRUNR_JOBS.SCHEDULEDAT, "SCHEDULED".equals(state) ? naiveUtc : null)
        .set(JOBRUNR_JOBS.RECURRINGJOBID, jobId)
        .execute();
  }

  // ── servlet doubles ───────────────────────────────────────────────────────

  private SecurityContext platform(SystemRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(role), Map.of(), Set.of(), 0);
  }

  private SecurityContext orgOwnerOnly() {
    return new SecurityContext(
        UUID.randomUUID(),
        ActorType.USER,
        Set.of(),
        Map.of(UUID.randomUUID(), Set.of(OrgRole.OWNER)),
        Set.of(),
        0);
  }

  private HttpServletRequest reqWith(SecurityContext ctx) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    return req;
  }

  private static final class Resp {
    final HttpServletResponse mock;
    final ByteArrayOutputStream body = new ByteArrayOutputStream();
    int status = 200;

    Resp() throws IOException {
      mock = Mockito.mock(HttpServletResponse.class);
      Mockito.doAnswer(
              inv -> {
                status = inv.getArgument(0);
                return null;
              })
          .when(mock)
          .setStatus(Mockito.anyInt());
      when(mock.getOutputStream())
          .thenReturn(
              new ServletOutputStream() {
                @Override
                public void write(int b) {
                  body.write(b);
                }

                @Override
                public boolean isReady() {
                  return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {}
              });
    }
  }
}
