package com.loai.inventory.api.admin;

import static com.loai.inventory.repository.generated.Tables.CREDIT_NOTE;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.FULFILLMENT;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY_EMAIL;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.REFUND;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.servlet.handler.OverviewAdminHandler;
import com.loai.inventory.api.servlet.handler.QueuesAdminHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.PlatformQueueKind;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.domain.repository.PaymentTransactionRepository;
import com.loai.inventory.repository.PaymentRepositoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryImpl;
import com.loai.inventory.repository.PlatformQueueRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformStatsRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryImpl;
import com.loai.inventory.repository.generated.enums.CreditNoteReason;
import com.loai.inventory.repository.generated.enums.CreditNoteStatus;
import com.loai.inventory.repository.generated.enums.FulfillmentStatus;
import com.loai.inventory.repository.generated.enums.InvoiceStatus;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.PaymentDirection;
import com.loai.inventory.repository.generated.enums.PaymentProvider;
import com.loai.inventory.repository.generated.enums.PaymentReconciliationStatus;
import com.loai.inventory.repository.generated.enums.PaymentStatus;
import com.loai.inventory.repository.generated.enums.PaymentVerificationStatus;
import com.loai.inventory.repository.generated.enums.RefundStatus;
import com.loai.inventory.service.platform.PlatformOverviewService;
import com.loai.inventory.service.platform.PlatformQueueService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration coverage for {@code GET /api/admin/queues/{kind}} ({@code
 * stories/platform_queues.md}) — the drill-down behind the overview's five backlog tiles.
 *
 * <p><strong>Every fixture seeds two orgs.</strong> A single-org fixture cannot fail the way this
 * slice can: a missing org column, a queue that quietly narrows to one tenant, and a {@code
 * ?org_id=} filter that does nothing all look identical with one tenant in the database.
 *
 * <p>The handlers are driven directly (rather than through Tomcat) so the authz matrix and the
 * 400/405 contract are exercised against the real service and a real database.
 */
@Testcontainers
class PlatformQueuesIT {

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

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ObjectMapper mapper;

  // Org-scoped repositories — the "other side" of the parity assertions.
  static RefundRepositoryImpl refundRepo;
  static PaymentRepositoryImpl paymentRepo;
  static PaymentTransactionRepositoryImpl transactionRepo;

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
        "TRUNCATE refund, credit_note, sales_invoice, fulfillment, payment, payment_transaction,"
            + " sales_order, notification_delivery, notification, customer, org RESTART IDENTITY"
            + " CASCADE");
  }

  // The pin the shared predicate exists to make trivially true

  /**
   * The queue's {@code total} and the overview tile are one number from one predicate ({@code
   * PlatformQueuePredicates}). This asserts the outcome; the guarantee is that neither side writes
   * the rule — a reviewer can see that without running this.
   */
  @ParameterizedTest
  @EnumSource(PlatformQueueKind.class)
  void eachQueue_totalEqualsTheOverviewCount(PlatformQueueKind kind) {
    seedEveryQueueInTwoOrgs();

    long total = list(kind).path("total").asLong();
    long tile = overview().path("queues").path(countField(kind)).asLong();

    assertEquals(tile, total, kind.wire() + ": list total and overview tile disagree");
    // Non-zero on both sides, so the assertion above is agreement and not shared emptiness.
    assertTrue(total > 0, kind.wire() + ": fixture seeded nothing");
  }

  /**
   * Where an org-scoped worklist exists, the platform queue is its cross-org union: Σ per-org total
   * == the platform total.
   *
   * <p>{@code failed-emails} and {@code expired-pending-orders} are deliberately absent from this
   * test because they have <strong>no org-scoped twin</strong>. A failed email delivery is
   * invisible on every other surface in this product, and the org order worklist filters on {@code
   * status}, not on {@code expires_at < now()}. For those two the platform queue is the only view
   * that will ever exist, so there is nothing to compare against.
   */
  @Test
  void eachQueue_matchesItsOrgScopedListWhereOneExists() {
    Orgs orgs = seedEveryQueueInTwoOrgs();

    assertEquals(
        refundRepo.count(orgs.acme, com.loai.inventory.domain.model.RefundStatus.PENDING, null)
            + refundRepo.count(
                orgs.beta, com.loai.inventory.domain.model.RefundStatus.PENDING, null),
        list(PlatformQueueKind.PENDING_REFUNDS).path("total").asLong());

    assertEquals(
        paymentRepo.count(orgs.acme, com.loai.inventory.domain.model.PaymentStatus.DISPUTED, false)
            + paymentRepo.count(
                orgs.beta, com.loai.inventory.domain.model.PaymentStatus.DISPUTED, false),
        list(PlatformQueueKind.OPEN_DISPUTES).path("total").asLong());

    assertEquals(
        transactionRepo.count(orgs.acme, openOrphanFilter())
            + transactionRepo.count(orgs.beta, openOrphanFilter()),
        list(PlatformQueueKind.ORPHAN_TRANSACTIONS).path("total").asLong());
  }

  // Tenancy on the row

  /** Unfiltered names both tenants; {@code ?org_id=} narrows the rows and the total together. */
  @ParameterizedTest
  @EnumSource(PlatformQueueKind.class)
  void rows_carryTheirTenant_andOrgIdFiltersToOne(PlatformQueueKind kind) {
    Orgs orgs = seedEveryQueueInTwoOrgs();

    JsonNode all = list(kind);
    Set<String> slugs = orgSlugs(all);
    assertTrue(slugs.contains("acme"), kind.wire() + ": acme missing from an unfiltered queue");
    assertTrue(slugs.contains("beta"), kind.wire() + ": beta missing from an unfiltered queue");
    all.path("data").forEach(row -> assertNotNull(row.path("org").path("id").asText()));

    JsonNode narrowed = list(kind, "?org_id=" + orgs.acme);
    assertEquals(Set.of("acme"), orgSlugs(narrowed));
    assertTrue(
        narrowed.path("total").asLong() < all.path("total").asLong(),
        kind.wire() + ": ?org_id= did not narrow the total");
    assertEquals(narrowed.path("data").size(), narrowed.path("total").asLong());
  }

  /**
   * The money a locked-out merchant owes is the money most likely to go unworked, so it must be the
   * one thing this queue cannot hide. Suspending a tenant changes nothing about its rows except the
   * status they report.
   */
  @Test
  void suspendedOrgsRowsStillAppear() {
    Orgs orgs = seedEveryQueueInTwoOrgs();
    long before = list(PlatformQueueKind.PENDING_REFUNDS).path("total").asLong();

    dsl.update(ORG)
        .set(ORG.ACTIVE, false)
        .set(ORG.SUSPENDED_AT, now())
        .set(ORG.SUSPENDED_REASON, "chargeback fraud")
        .where(ORG.ID.eq(orgs.beta))
        .execute();

    JsonNode after = list(PlatformQueueKind.PENDING_REFUNDS);
    assertEquals(before, after.path("total").asLong(), "suspension must not shrink a queue");

    Map<String, JsonNode> byTenant = new HashMap<>();
    after.path("data").forEach(row -> byTenant.put(row.path("org").path("slug").asText(), row));
    assertTrue(byTenant.containsKey("beta"), "the suspended tenant's refund vanished");
    assertEquals("suspended", byTenant.get("beta").path("org").path("status").asText());
    // …and its neighbour is unaffected: the status is per-row, not a page-level flag.
    assertEquals("active", byTenant.get("acme").path("org").path("status").asText());
  }

  // The whitelist

  /**
   * <strong>The whitelist pin.</strong> Every kind is serialized from a fixture whose customer has
   * a name, a phone and an address, and none of those values may appear anywhere in the JSON. The
   * invoice behind the credit-note-backed refund freezes the same three fields, so this also covers
   * the lookup joins dragging PII along behind a legitimate number.
   *
   * <p>This is what keeps rule 3 of {@code PlatformQueueRepository} true as fields get added later.
   */
  @Test
  void rows_carryNoCustomerPii() {
    seedEveryQueueInTwoOrgs();

    for (PlatformQueueKind kind : PlatformQueueKind.values()) {
      String json = list(kind).toString();
      assertTrue(list(kind).path("data").size() > 0, kind.wire() + ": nothing to inspect");
      for (String secret :
          List.of(CUSTOMER_NAME, CUSTOMER_PHONE, CUSTOMER_ADDRESS, CUSTOMER_NOTE_ON_ORDER)) {
        assertFalse(json.contains(secret), kind.wire() + " leaked '" + secret + "'");
      }
    }
  }

  /**
   * The one deliberate PII crossing, asserted on purpose so removing it later is a visible decision
   * rather than a silent regression. A failed-delivery queue without the recipient cannot be
   * triaged — you cannot tell three unrelated failures from three failures to one dead domain.
   */
  @Test
  void failedEmails_carryTheRecipientAndTheError() {
    seedEveryQueueInTwoOrgs();

    JsonNode row = list(PlatformQueueKind.FAILED_EMAILS).path("data").get(0);

    assertTrue(row.path("to_address").asText().endsWith("@dead-domain.test"));
    assertEquals("smtp 550 mailbox unavailable", row.path("last_error").asText());
    assertEquals("ORDER_PLACED", row.path("notification_type").asText());
    assertTrue(row.path("attempts").asInt() > 0);
    assertTrue(row.hasNonNull("failed_at"));
  }

  /** A credit-note-backed refund names its note; a payment-backed one names its order. */
  @Test
  void pendingRefunds_carryWhicheverSourceNumberExists() {
    seedEveryQueueInTwoOrgs();

    List<JsonNode> rows = new java.util.ArrayList<>();
    list(PlatformQueueKind.PENDING_REFUNDS).path("data").forEach(rows::add);

    assertTrue(
        rows.stream().anyMatch(r -> r.path("sales_order_number").asText().startsWith("SO-")),
        "no payment-backed refund named its order");
    assertTrue(
        rows.stream().anyMatch(r -> r.path("credit_note_number").asText().startsWith("CN-")),
        "no credit-note-backed refund named its note");
    // Exactly one of the two, never both — refund_check enforces exactly one source.
    rows.forEach(
        r ->
            assertFalse(
                r.hasNonNull("sales_order_number") && r.hasNonNull("credit_note_number"),
                "a refund claimed both an order and a credit note"));
  }

  // Ordering + paging

  /** Oldest-first, always. There is no ledger mode: all five kinds are queues by construction. */
  @Test
  void queuesAreOldestFirst_andPageWithinTheirTotal() {
    UUID acme = createOrg("acme");
    UUID beta = createOrg("beta");
    // Interleaved across tenants so a per-org ordering bug would show as a scrambled sequence.
    for (int i = 0; i < 6; i++) {
      createPendingRefund(i % 2 == 0 ? acme : beta, now().minusDays(10 - i));
    }

    JsonNode page = list(PlatformQueueKind.PENDING_REFUNDS, "?size=4");
    assertEquals(6, page.path("total").asLong());
    assertEquals(4, page.path("data").size());

    List<Instant> stamps = new java.util.ArrayList<>();
    page.path("data").forEach(r -> stamps.add(Instant.parse(r.path("created_at").asText())));
    for (int i = 1; i < stamps.size(); i++) {
      assertTrue(!stamps.get(i).isBefore(stamps.get(i - 1)), "queue is not oldest-first");
    }

    JsonNode second = list(PlatformQueueKind.PENDING_REFUNDS, "?size=4&page=1");
    assertEquals(2, second.path("data").size());
    assertEquals(6, second.path("total").asLong(), "total must not move between pages");
  }

  // Input contract

  /** An id no tenant holds is a filter that matches nothing — not a lookup that 404s. */
  @Test
  void unknownOrgId_isAnEmptyPage_notA404() {
    seedEveryQueueInTwoOrgs();

    JsonNode body = list(PlatformQueueKind.PENDING_REFUNDS, "?org_id=" + UUID.randomUUID());

    assertEquals(0, body.path("data").size());
    assertEquals(0, body.path("total").asLong());
  }

  @Test
  void malformedOrgId_is400() {
    Resp resp = invoke("GET", platform(SystemRole.ADMIN), "/pending-refunds", "?org_id=not-a-uuid");
    assertEquals(400, resp.status);
    assertTrue(bodyOf(resp).path("message").asText().contains("org_id"));
  }

  /** A 400 naming the five, not a 404: {@code kind} is an enum value that sits in the path. */
  @Test
  void unknownKind_is400NamingTheFive() {
    Resp resp = invoke("GET", platform(SystemRole.ADMIN), "/pending-invoices", "");
    assertEquals(400, resp.status);

    String message = bodyOf(resp).path("message").asText();
    for (PlatformQueueKind kind : PlatformQueueKind.values()) {
      assertTrue(message.contains(kind.wire()), "400 did not name " + kind.wire());
    }
  }

  /** The reserved-route 400, mirroring {@code GET /sales-orders} and {@code GET /credit-notes}. */
  @Test
  void bareQueuesRoot_is400() {
    assertEquals(400, invoke("GET", platform(SystemRole.ADMIN), "", "").status);
    assertEquals(400, invoke("GET", platform(SystemRole.ADMIN), "/", "").status);
  }

  @Test
  void post_is405() {
    assertEquals(405, invoke("POST", platform(SystemRole.ADMIN), "/pending-refunds", "").status);
  }

  // Authz

  /** The whole resource is a read, so SUPPORT sees it — and sees exactly what ADMIN sees. */
  @Test
  void support_reads200() {
    seedEveryQueueInTwoOrgs();

    JsonNode support = read(platform(SystemRole.SUPPORT), "/pending-refunds", "", 200);
    JsonNode admin = read(platform(SystemRole.ADMIN), "/pending-refunds", "", 200);

    assertTrue(support.path("total").asLong() > 0);
    assertEquals(admin, support, "nothing on this read is tier-gated");
  }

  /** An org OWNER holds no platform role, so the platform tier is not theirs to read. */
  @Test
  void orgOwner_is403() {
    assertEquals(403, invoke("GET", orgOwnerOnly(), "/pending-refunds", "").status);
  }

  @Test
  void anonymous_is401() {
    assertEquals(401, invoke("GET", null, "/pending-refunds", "").status);
  }

  // plumbing

  private static final String CUSTOMER_NAME = "Nadia Abdelrahman";
  private static final String CUSTOMER_PHONE = "+201005550123";
  private static final String CUSTOMER_ADDRESS = "17 Sharia El-Nil, Zamalek, Cairo";
  private static final String CUSTOMER_NOTE_ON_ORDER = "leave with the bawab";

  private QueuesAdminHandler handler() {
    return new QueuesAdminHandler(
        new PlatformQueueService(dsl, new PlatformQueueRepositoryFactoryImpl()), mapper);
  }

  private JsonNode list(PlatformQueueKind kind) {
    return list(kind, "");
  }

  private JsonNode list(PlatformQueueKind kind, String query) {
    return read(platform(SystemRole.ADMIN), "/" + kind.wire(), query, 200);
  }

  private JsonNode read(SecurityContext ctx, String remaining, String query, int expectedStatus) {
    Resp resp = invoke("GET", ctx, remaining, query);
    assertEquals(expectedStatus, resp.status, () -> "body was " + resp.body.toString());
    return bodyOf(resp);
  }

  /** The overview read, so the parity assertion runs against the real tile, not a re-count. */
  private JsonNode overview() {
    try {
      Resp resp = new Resp();
      new OverviewAdminHandler(
              new PlatformOverviewService(
                  dsl,
                  new PlatformStatsRepositoryFactoryImpl(),
                  false,
                  List.of(),
                  null,
                  Instant.now()),
              mapper)
          .handle("GET", reqWith(platform(SystemRole.ADMIN), ""), resp.mock, "");
      assertEquals(200, resp.status);
      return bodyOf(resp);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String countField(PlatformQueueKind kind) {
    return kind.name().toLowerCase(java.util.Locale.ROOT);
  }

  private Set<String> orgSlugs(JsonNode page) {
    Set<String> slugs = new java.util.LinkedHashSet<>();
    page.path("data").forEach(row -> slugs.add(row.path("org").path("slug").asText()));
    return slugs;
  }

  private Resp invoke(String method, SecurityContext ctx, String remaining, String query) {
    try {
      Resp resp = new Resp();
      handler().handle(method, reqWith(ctx, query), resp.mock, remaining);
      return resp;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode bodyOf(Resp resp) {
    try {
      return mapper.readTree(resp.body.toByteArray());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static PaymentTransactionRepository.ListFilter openOrphanFilter() {
    return new PaymentTransactionRepository.ListFilter(
        null,
        com.loai.inventory.domain.model.PaymentReconciliationStatus.ORPHAN,
        false,
        null,
        null);
  }

  // fixtures

  /** The two tenants every fixture in this class seeds. */
  private record Orgs(UUID acme, UUID beta) {}

  /**
   * One row of every kind in <em>each</em> of two tenants, plus one row of every kind that is
   * deliberately <em>out</em> of its queue, so an assertion cannot pass by counting everything.
   */
  private Orgs seedEveryQueueInTwoOrgs() {
    UUID acme = createOrg("acme");
    UUID beta = createOrg("beta");
    for (UUID org : List.of(acme, beta)) {
      createPendingRefund(org, now().minusDays(2));
      createDisputedPayment(org);
      createOrphanTransaction(org);
      createExpiredPendingOrder(org);
      createFailedEmail(org);

      // …and the near-misses, one per queue.
      createRefund(org, RefundStatus.EXECUTED);
      createPayment(org, PaymentStatus.ALLOCATED);
      createResolvedOrphanTransaction(org);
      createOrder(org, OrderStatus.PENDING_PAYMENT, now().plusHours(1));
      createDelivery(org, "SENT");
    }
    // One credit-note-backed refund, in acme only — the other branch of the refund source join.
    createCreditNoteBackedRefund(acme);
    return new Orgs(acme, beta);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private UUID createOrg(String slug) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG).set(ORG.ID, id).set(ORG.NAME, slug).set(ORG.SLUG, slug).execute();
    return id;
  }

  /** A customer with every PII field populated — the bait for {@link #rows_carryNoCustomerPii}. */
  private UUID createCustomer(UUID orgId) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, orgId)
        .set(CUSTOMER.EMAIL, "shopper-" + seq.getAndIncrement() + "@dead-domain.test")
        .set(CUSTOMER.NAME, CUSTOMER_NAME)
        .set(CUSTOMER.PHONE, CUSTOMER_PHONE)
        .set(CUSTOMER.ADDRESS, CUSTOMER_ADDRESS)
        .execute();
    return id;
  }

  private UUID createOrder(UUID orgId, OrderStatus status, OffsetDateTime expiresAt) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, id)
        .set(SALES_ORDER.ORG_ID, orgId)
        .set(SALES_ORDER.CUSTOMER_ID, createCustomer(orgId))
        .set(SALES_ORDER.ORDER_NUMBER, "SO-" + seq.getAndIncrement())
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, status)
        .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal("250.00"))
        .set(SALES_ORDER.PLACED_AT, now().minusDays(3))
        .set(SALES_ORDER.EXPIRES_AT, expiresAt)
        .set(SALES_ORDER.NOTES, CUSTOMER_NOTE_ON_ORDER)
        .execute();
    return id;
  }

  private void createExpiredPendingOrder(UUID orgId) {
    createOrder(orgId, OrderStatus.PENDING_PAYMENT, now().minusHours(1));
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
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, now().minusDays(1))
        .set(PAYMENT_TRANSACTION.CUSTOMER_NOTE, CUSTOMER_NOTE_ON_ORDER)
        .set(PAYMENT_TRANSACTION.CLAIMED_BY_CUSTOMER_ID, createCustomer(orgId))
        .execute();
    return id;
  }

  private UUID createPayment(UUID orgId, PaymentStatus status) {
    return attachPayment(
        orgId, createTransaction(orgId, PaymentReconciliationStatus.MATCHED), status, null);
  }

  private UUID attachPayment(
      UUID orgId, UUID transactionId, PaymentStatus status, UUID salesOrderId) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PAYMENT)
        .set(PAYMENT.ID, id)
        .set(PAYMENT.ORG_ID, orgId)
        .set(PAYMENT.PAYMENT_TRANSACTION_ID, transactionId)
        .set(PAYMENT.SALES_ORDER_ID, salesOrderId)
        .set(PAYMENT.AMOUNT, new BigDecimal("100.00"))
        .set(PAYMENT.UNALLOCATED_AMOUNT, BigDecimal.ZERO)
        .set(PAYMENT.STATUS, status)
        .set(PAYMENT.RECEIVED_AT, now().minusDays(1))
        .execute();
    return id;
  }

  /** DISPUTED, bound to an order so the row has a number to name. */
  private void createDisputedPayment(UUID orgId) {
    attachPayment(
        orgId,
        createTransaction(orgId, PaymentReconciliationStatus.MATCHED),
        PaymentStatus.DISPUTED,
        createOrder(orgId, OrderStatus.PAID, null));
  }

  /** A payment-backed PENDING refund whose payment names an order. */
  private void createPendingRefund(UUID orgId, OffsetDateTime createdAt) {
    UUID payment =
        attachPayment(
            orgId,
            createTransaction(orgId, PaymentReconciliationStatus.MATCHED),
            PaymentStatus.RECEIVED,
            createOrder(orgId, OrderStatus.CANCELLED, null));
    insertRefund(orgId, RefundStatus.PENDING, payment, null, createdAt);
  }

  private void createRefund(UUID orgId, RefundStatus status) {
    insertRefund(orgId, status, createPayment(orgId, PaymentStatus.RECEIVED), null, now());
  }

  /** A CreditNote-backed PENDING refund: fulfillment → invoice → credit note → refund. */
  private void createCreditNoteBackedRefund(UUID orgId) {
    UUID customer = createCustomer(orgId);
    UUID order = createOrder(orgId, OrderStatus.FULFILLED, null);
    UUID fulfillment = UUID.randomUUID();
    dsl.insertInto(FULFILLMENT)
        .set(FULFILLMENT.ID, fulfillment)
        .set(FULFILLMENT.ORG_ID, orgId)
        .set(FULFILLMENT.SALES_ORDER_ID, order)
        .set(FULFILLMENT.STATUS, FulfillmentStatus.DELIVERED)
        .execute();
    UUID invoice = UUID.randomUUID();
    dsl.insertInto(SALES_INVOICE)
        .set(SALES_INVOICE.ID, invoice)
        .set(SALES_INVOICE.ORG_ID, orgId)
        .set(SALES_INVOICE.CUSTOMER_ID, customer)
        .set(SALES_INVOICE.SALES_ORDER_ID, order)
        .set(SALES_INVOICE.FULFILLMENT_ID, fulfillment)
        .set(SALES_INVOICE.INVOICE_NUMBER, "INV-" + seq.getAndIncrement())
        .set(SALES_INVOICE.STATUS, InvoiceStatus.ISSUED)
        .set(SALES_INVOICE.SUBTOTAL, new BigDecimal("100.00"))
        .set(SALES_INVOICE.GRAND_TOTAL, new BigDecimal("100.00"))
        // Frozen PII on the invoice: the credit-note join must not drag it onto a queue row.
        .set(SALES_INVOICE.CUSTOMER_NAME, CUSTOMER_NAME)
        .set(SALES_INVOICE.CUSTOMER_PHONE, CUSTOMER_PHONE)
        .set(SALES_INVOICE.CUSTOMER_ADDRESS, CUSTOMER_ADDRESS)
        .execute();
    UUID creditNote = UUID.randomUUID();
    dsl.insertInto(CREDIT_NOTE)
        .set(CREDIT_NOTE.ID, creditNote)
        .set(CREDIT_NOTE.ORG_ID, orgId)
        .set(CREDIT_NOTE.CUSTOMER_ID, customer)
        .set(CREDIT_NOTE.SALES_INVOICE_ID, invoice)
        .set(CREDIT_NOTE.REASON, CreditNoteReason.RETURN)
        .set(CREDIT_NOTE.SUBTOTAL, new BigDecimal("100.00"))
        .set(CREDIT_NOTE.TOTAL, new BigDecimal("100.00"))
        .set(CREDIT_NOTE.CREDIT_NOTE_NUMBER, "CN-" + seq.getAndIncrement())
        .set(CREDIT_NOTE.STATUS, CreditNoteStatus.ISSUED)
        .execute();
    insertRefund(orgId, RefundStatus.PENDING, null, creditNote, now().minusDays(1));
  }

  private void insertRefund(
      UUID orgId,
      RefundStatus status,
      UUID paymentId,
      UUID creditNoteId,
      OffsetDateTime createdAt) {
    dsl.insertInto(REFUND)
        .set(REFUND.ID, UUID.randomUUID())
        .set(REFUND.ORG_ID, orgId)
        .set(REFUND.PAYMENT_ID, paymentId)
        .set(REFUND.CREDIT_NOTE_ID, creditNoteId)
        .set(REFUND.AMOUNT, new BigDecimal("10.00"))
        .set(REFUND.STATUS, status)
        .set(REFUND.METHOD, PaymentProvider.instapay_manual)
        .set(REFUND.CREATED_AT, createdAt)
        .set(REFUND.NOTES, CUSTOMER_NOTE_ON_ORDER)
        .execute();
  }

  /** ORPHAN with no payment row — the open queue. */
  private void createOrphanTransaction(UUID orgId) {
    createTransaction(orgId, PaymentReconciliationStatus.ORPHAN);
  }

  /** ORPHAN that already has its 1:1 payment — resolved, so out of the queue. */
  private void createResolvedOrphanTransaction(UUID orgId) {
    UUID txn = createTransaction(orgId, PaymentReconciliationStatus.ORPHAN);
    attachPayment(orgId, txn, PaymentStatus.RECEIVED, null);
  }

  private void createFailedEmail(UUID orgId) {
    createDelivery(orgId, "FAILED");
  }

  private void createDelivery(UUID orgId, String status) {
    UUID customer = createCustomer(orgId);
    String recipient = "delivery-" + seq.getAndIncrement() + "@dead-domain.test";
    UUID notificationId = UUID.randomUUID();
    dsl.insertInto(NOTIFICATION)
        .set(NOTIFICATION.ID, notificationId)
        .set(NOTIFICATION.ORG_ID, orgId)
        .set(NOTIFICATION.RECIPIENT_TYPE, "CUSTOMER")
        .set(NOTIFICATION.RECIPIENT_CUSTOMER_ID, customer)
        .set(NOTIFICATION.TYPE, "ORDER_PLACED")
        .set(NOTIFICATION.TITLE, CUSTOMER_NAME + ", your order is placed")
        .set(NOTIFICATION.BODY, "Ship to " + CUSTOMER_ADDRESS)
        .set(NOTIFICATION.STATUS, "DISPATCHED")
        .execute();
    UUID deliveryId = UUID.randomUUID();
    boolean failed = "FAILED".equals(status);
    dsl.insertInto(NOTIFICATION_DELIVERY)
        .set(NOTIFICATION_DELIVERY.ID, deliveryId)
        .set(NOTIFICATION_DELIVERY.NOTIFICATION_ID, notificationId)
        .set(NOTIFICATION_DELIVERY.CHANNEL, "email")
        .set(NOTIFICATION_DELIVERY.STATUS, status)
        .set(NOTIFICATION_DELIVERY.ATTEMPTS, failed ? 5 : 1)
        .set(NOTIFICATION_DELIVERY.LAST_ERROR, failed ? "smtp 550 mailbox unavailable" : null)
        .set(NOTIFICATION_DELIVERY.FAILED_AT, failed ? now().minusHours(6) : null)
        .execute();
    dsl.insertInto(NOTIFICATION_DELIVERY_EMAIL)
        .set(NOTIFICATION_DELIVERY_EMAIL.DELIVERY_ID, deliveryId)
        .set(NOTIFICATION_DELIVERY_EMAIL.TO_ADDRESS, recipient)
        // The rendered body is full of customer PII and is exactly what must not cross.
        .set(NOTIFICATION_DELIVERY_EMAIL.SUBJECT, "Order for " + CUSTOMER_NAME)
        .set(
            NOTIFICATION_DELIVERY_EMAIL.RENDERED_HTML,
            "<p>" + CUSTOMER_ADDRESS + " " + CUSTOMER_PHONE + "</p>")
        .execute();
  }

  // servlet doubles

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

  /** A request carrying the security context and the query string's parameters. */
  private HttpServletRequest reqWith(SecurityContext ctx, String query) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    if (query != null && query.startsWith("?")) {
      for (String pair : query.substring(1).split("&")) {
        int eq = pair.indexOf('=');
        if (eq > 0) {
          when(req.getParameter(pair.substring(0, eq))).thenReturn(pair.substring(eq + 1));
        }
      }
    }
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
