package com.loai.inventory.api.admin;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.ORG_MILESTONE;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.servlet.handler.FunnelAdminHandler;
import com.loai.inventory.common.exception.InsufficientStockException;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.domain.repository.ImpersonationEventRepository;
import com.loai.inventory.repository.AppUserMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgHealthRepositoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformAuditRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformFunnelRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductVariantRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryImpl;
import com.loai.inventory.service.CouponService;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.ProductListingService;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.auth.AccountService;
import com.loai.inventory.service.auth.AuthMailer;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.auth.CredentialTokenService;
import com.loai.inventory.service.auth.RefreshTokenStore;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.loai.inventory.service.platform.OrgStatusService;
import com.loai.inventory.service.platform.PlatformAuditService;
import com.loai.inventory.service.platform.PlatformFunnelService;
import com.loai.inventory.service.platform.PlatformOrgService;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;

/**
 * Integration coverage for {@code GET /api/admin/funnel} and the {@code org_milestone} write sites
 * (slice 7, {@code stories/platform_tenant_funnel.md}).
 *
 * <p><strong>Both acquisition paths, real service call sites.</strong> Milestones are stamped by
 * the same transactions that cause them — registration, verification, provisioning, listing
 * create/publish, order placement, payment recording — so every test here drives the real service
 * rather than hand-inserting rows, except where the point is precisely a hand-inserted row (the
 * open-text guarantee, the backfill).
 */
@Testcontainers
class PlatformFunnelIT {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ObjectMapper mapper;
  static AccountService accountService;
  static PlatformOrgService platformOrgService;
  static ProductListingService listingService;
  static SalesOrderService salesOrderService;
  static CredentialTokenService credentialTokenService;
  static FunnelAdminHandler handler;

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

    OrgMilestoneService milestones =
        new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl());
    OrgRepositoryFactoryImpl orgRepoFactory = new OrgRepositoryFactoryImpl();
    UserRepositoryFactoryImpl userRepoFactory = new UserRepositoryFactoryImpl();
    JedisPool jedisPool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
    OrgStatusService orgStatus = new OrgStatusService(jedisPool, dsl, orgRepoFactory);
    credentialTokenService =
        new CredentialTokenService(
            dsl,
            new AppUserMagicTokenRepositoryFactoryImpl(),
            "http://localhost:8080",
            Duration.ofMinutes(120),
            Duration.ofDays(7),
            Duration.ofHours(48));

    AuthService authService =
        new AuthService(
            new UserRepositoryImpl(dsl),
            new RefreshTokenStore(jedisPool),
            new JwtUtil(Base64.getEncoder().encodeToString(new byte[48]), 900_000L),
            Mockito.mock(ImpersonationEventRepository.class),
            300_000L);
    accountService =
        new AccountService(
            dsl,
            userRepoFactory,
            orgRepoFactory,
            credentialTokenService,
            new AuthMailer(msg -> {}),
            authService,
            com.loai.inventory.api.support.TestWiring.permissiveEmailGate(),
            orgStatus,
            milestones);
    platformOrgService =
        new PlatformOrgService(
            dsl,
            orgRepoFactory,
            userRepoFactory,
            new OrgHealthRepositoryImpl(dsl),
            new PlatformAuditService(dsl, new PlatformAuditRepositoryFactoryImpl()),
            orgStatus,
            credentialTokenService,
            new AuthMailer(msg -> {}),
            milestones);
    listingService =
        new ProductListingService(
            dsl,
            new ProductListingRepositoryFactoryImpl(),
            orgRepoFactory,
            new ProductVariantRepositoryFactoryImpl(),
            com.loai.inventory.common.storage.ObjectStorageFactory.build(),
            milestones);

    ReservationService reservationService =
        new ReservationService(
            new InventoryRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl());
    InvoiceService invoiceService =
        new InvoiceService(
            new SalesInvoiceRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl());
    RefundService refundService =
        new RefundService(
            dsl,
            new RefundRepositoryFactoryImpl(),
            new RefundAllocationRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            orgRepoFactory,
            new SalesOrderRepositoryFactoryImpl());
    FulfillmentService fulfillmentService =
        new FulfillmentService(
            dsl,
            new FulfillmentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            invoiceService,
            refundService,
            reservationService,
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl));
    PaymentService paymentService =
        new PaymentService(
            dsl,
            new PaymentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl),
            milestones);
    salesOrderService =
        new SalesOrderService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            orgRepoFactory,
            reservationService,
            fulfillmentService,
            paymentService,
            invoiceService,
            refundService,
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl),
            com.loai.inventory.api.support.TestWiring.permissiveEmailGate(),
            new CouponService(dsl, new com.loai.inventory.repository.CouponRepositoryFactoryImpl()),
            milestones);

    handler =
        new FunnelAdminHandler(
            new PlatformFunnelService(dsl, new PlatformFunnelRepositoryFactoryImpl()), mapper);
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
        "TRUNCATE org_milestone, platform_audit, app_user_magic_token, payment_allocation,"
            + " sales_invoice_line, sales_invoice, payment, payment_transaction, fulfillment_line,"
            + " fulfillment, inventory_reservation, inventory_log, inventory, sales_order_line,"
            + " sales_order, customer, product_listing, product, user_system_role, user_org_role,"
            + " app_user, org, order_number_counter, invoice_number_counter"
            + " RESTART IDENTITY CASCADE");
  }

  // ------------------------------------------------------------ the test the migration exists for

  /**
   * <strong>The load-bearing test.</strong> Publish → unpublish → republish, and {@code
   * PUBLISHED.reached_at} is still the <em>first</em> publish throughout. {@code MIN(published_at)}
   * cannot survive that sequence ({@code unpublish} nulls the column — ProductListingService:415),
   * which is the whole reason {@code org_milestone} exists.
   */
  @Test
  void stagesAreStampedOnce_andNeverMove() {
    UUID org = createActiveOrg("acme");
    UUID product = createProduct(org, "SKU1");
    var listing = listingService.create(org, product, "Widget", null, "widget", price("10"));

    listingService.publish(org, listing.getId());
    OffsetDateTime firstStamp = reachedAt(org, "PUBLISHED");
    assertNotNull(firstStamp, "first publish did not stamp PUBLISHED");

    listingService.unpublish(org, listing.getId());
    assertEquals(
        firstStamp, reachedAt(org, "PUBLISHED"), "unpublish moved (or cleared) the milestone");

    listingService.publish(org, listing.getId());
    assertEquals(firstStamp, reachedAt(org, "PUBLISHED"), "republish moved the milestone");
    assertEquals(1, milestoneCount(org, "PUBLISHED"), "republish wrote a second row");

    // The contrast that motivates the table: the listing's own published_at DID move.
    OffsetDateTime republishedAt =
        dsl.select(com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING.PUBLISHED_AT)
            .from(com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING)
            .where(
                com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING.ID.eq(
                    listing.getId()))
            .fetchOne(com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING.PUBLISHED_AT);
    assertTrue(
        republishedAt.isAfter(firstStamp),
        "fixture control: the republish should carry a later published_at than the milestone");
  }

  /**
   * An {@code IN_STORE}-only merchant reaches FIRST_ORDER (and FIRST_PAYMENT) having never reached
   * PUBLISHED — and the funnel reports exactly that, stage 5 exceeding stage 4, unclamped.
   */
  @Test
  void inStoreOnlyTenant_reachesFirstOrderWithoutPublished() {
    UUID org = createActiveOrg("counter-shop");
    UUID staff = createUser("cashier@counter.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10);

    salesOrderService.placeInStoreSale(
        org,
        null,
        List.of(new SalesOrderService.OrderLineInput(product, 1)),
        new SalesOrderService.PaymentInput(PaymentProvider.CASH, null, null),
        null,
        actor(staff),
        UUID.randomUUID().toString(),
        staff);

    JsonNode stages = funnel("?cohort=all&path=all").path("stages");
    assertEquals(0, reachedOf(stages, "PUBLISHED"));
    assertEquals(0, reachedOf(stages, "CATALOGUED"));
    assertEquals(1, reachedOf(stages, "FIRST_ORDER"));
    assertEquals(1, reachedOf(stages, "FIRST_PAYMENT"));
  }

  // ------------------------------------------------------------ the two activation paths

  @Test
  void provisionedTenantIsActivatedAtCreation() {
    UUID admin = createUser("ops@platform.test");
    PlatformOrgService.ProvisionResult result =
        platformOrgService.provision(
            platformCtx(admin), env(), "Gamma Goods", "gamma", "owner@gamma.test");

    UUID org = result.org().getId();
    assertNotNull(reachedAt(org, "REGISTERED"));
    assertEquals(
        reachedAt(org, "REGISTERED"),
        reachedAt(org, "ACTIVATED"),
        "a provisioned tenant is born activated — same instant, both stamps");
  }

  @Test
  void selfServeTenantIsActivatedAtVerification() {
    AppUser owner = accountService.register("founder@shop.test", "correct-horse", "Founder Shop");
    UUID org = soleOrgOf(owner.getId());

    assertNotNull(reachedAt(org, "REGISTERED"), "registration must stamp REGISTERED");
    assertNull(reachedAt(org, "ACTIVATED"), "an unverified signup has not activated");

    accountService.verifyEmail(
        verifyTokenFor(owner.getId()), OffsetDateTime.now(), "junit", "127.0.0.1");

    assertNotNull(reachedAt(org, "ACTIVATED"), "the verification click is the activation event");
  }

  /** A provisioned tenant never appears in {@code path=self_serve}, and {@code all} is the sum. */
  @Test
  void pathFilterSeparatesTheTwoFunnels() {
    AppUser owner = accountService.register("founder@shop.test", "correct-horse", "Founder Shop");
    accountService.verifyEmail(
        verifyTokenFor(owner.getId()), OffsetDateTime.now(), "junit", "127.0.0.1");
    UUID admin = createUser("ops@platform.test");
    platformOrgService.provision(
        platformCtx(admin), env(), "Gamma Goods", "gamma", "owner@gamma.test");

    JsonNode selfServe = funnel("?cohort=all&path=self_serve");
    JsonNode provisioned = funnel("?cohort=all&path=provisioned");
    JsonNode all = funnel("?cohort=all&path=all");

    assertEquals(1, selfServe.path("cohort").path("size").asLong());
    assertEquals(1, provisioned.path("cohort").path("size").asLong());
    assertEquals(2, all.path("cohort").path("size").asLong());
    assertEquals(1, reachedOf(selfServe.path("stages"), "REGISTERED"));
    assertEquals(1, reachedOf(provisioned.path("stages"), "REGISTERED"));
    assertEquals(2, reachedOf(all.path("stages"), "REGISTERED"));
    assertEquals(
        reachedOf(all.path("stages"), "ACTIVATED"),
        reachedOf(selfServe.path("stages"), "ACTIVATED")
            + reachedOf(provisioned.path("stages"), "ACTIVATED"),
        "all must be the sum of the two paths");
  }

  // ------------------------------------------------------------ cohort arithmetic

  @Test
  void cohortExcludesOlderTenants() {
    UUID recent = createActiveOrg("recent");
    UUID ancient = createActiveOrg("ancient");
    backdateOrg(ancient, 400);

    JsonNode windowed = funnel("?cohort=30d&path=all");
    JsonNode all = funnel("?cohort=all&path=all");

    assertEquals(1, windowed.path("cohort").path("size").asLong());
    assertEquals(1, reachedOf(windowed.path("stages"), "REGISTERED"));
    assertEquals(2, all.path("cohort").path("size").asLong());
    assertEquals(2, reachedOf(all.path("stages"), "REGISTERED"));
    assertNotNull(recent); // both fixtures are load-bearing; recent is the one that stays
  }

  /** The denominator is on the response — the client never computes one. */
  @Test
  void cohortSizeIsTheDenominatorOnTheResponse() {
    createActiveOrg("one");
    createActiveOrg("two");
    createActiveOrg("three");

    JsonNode cohort = funnel("?cohort=all&path=all").path("cohort");
    assertEquals(3, cohort.path("size").asLong());
    assertEquals("all", cohort.path("window").asText());
  }

  @Test
  void youngestAgeDaysReflectsTheNewestMemberOfTheCohort() {
    UUID older = createActiveOrg("older");
    backdateOrg(older, 20);
    UUID younger = createActiveOrg("younger");
    backdateOrg(younger, 3);

    long youngest =
        funnel("?cohort=30d&path=all").path("cohort").path("youngest_age_days").asLong();
    assertEquals(3, youngest, "youngest_age_days must track the newest member, not the oldest");
  }

  /** An empty cohort is a size-0 response with all six stages at zero — never an error. */
  @Test
  void emptyCohort_isACalmZeroResponse() {
    JsonNode body = funnel("?cohort=30d&path=all");
    assertEquals(0, body.path("cohort").path("size").asLong());
    assertTrue(
        body.path("cohort").path("youngest_age_days").isMissingNode(),
        "an empty cohort has no youngest member to report an age for");
    assertEquals(6, body.path("stages").size(), "all six stages render even over nobody");
    for (JsonNode stage : body.path("stages")) {
      assertEquals(0, stage.path("reached").asLong());
    }
  }

  // ------------------------------------------------------------ backfill

  /**
   * V77's backfill statements, run verbatim against pre-migration-shaped data (rows in the source
   * tables, nothing in {@code org_milestone}), recover each stage from its documented source.
   */
  @Test
  void backfill_recoversEachStageFromItsSource() {
    // A self-serve org: owner verified, catalogued, published (still live), sold, got paid.
    AppUser owner = accountService.register("founder@shop.test", "correct-horse", "Founder Shop");
    accountService.verifyEmail(
        verifyTokenFor(owner.getId()), OffsetDateTime.now(), "junit", "127.0.0.1");
    UUID selfServe = soleOrgOf(owner.getId());
    UUID product = createProduct(selfServe, "SKU1");
    createInventory(selfServe, product, 10);
    var listing = listingService.create(selfServe, product, "W", null, "w", price("10"));
    listingService.publish(selfServe, listing.getId());
    UUID staff = createUser("cashier@shop.test");
    salesOrderService.placeInStoreSale(
        selfServe,
        null,
        List.of(new SalesOrderService.OrderLineInput(product, 1)),
        new SalesOrderService.PaymentInput(PaymentProvider.CASH, null, null),
        null,
        actor(staff),
        UUID.randomUUID().toString(),
        staff);
    // A provisioned org, its ORG_CREATE audit row being what the ACTIVATED split keys on.
    UUID admin = createUser("ops@platform.test");
    UUID provisioned =
        platformOrgService
            .provision(platformCtx(admin), env(), "Gamma Goods", "gamma", "owner@gamma.test")
            .org()
            .getId();

    // Pre-migration shape: the source tables hold everything, org_milestone holds nothing.
    dsl.deleteFrom(ORG_MILESTONE).execute();
    runV77Backfill();

    assertNotNull(reachedAt(selfServe, "REGISTERED"));
    assertEquals(
        ownerVerifiedAt(owner.getId()),
        reachedAt(selfServe, "ACTIVATED"),
        "self-serve ACTIVATED must recover from the owner's email_verified_at");
    assertNotNull(reachedAt(selfServe, "CATALOGUED"));
    assertNotNull(reachedAt(selfServe, "PUBLISHED"));
    assertNotNull(reachedAt(selfServe, "FIRST_ORDER"));
    assertNotNull(reachedAt(selfServe, "FIRST_PAYMENT"));
    assertEquals(
        orgCreatedAt(provisioned),
        reachedAt(provisioned, "ACTIVATED"),
        "provisioned ACTIVATED must recover as org.created_at, keyed on the ORG_CREATE audit row");
  }

  /**
   * The honest half of finding 1: a tenant that unpublished everything before the migration has no
   * durable trace of its first launch. The backfill leaves it <strong>unstamped</strong> — no
   * synthesised timestamp — and the residual query counts it.
   */
  @Test
  void backfill_leavesUnpublishedLaunchesUnstamped_andCountsThem() {
    UUID org = createActiveOrg("was-live-once");
    UUID product = createProduct(org, "SKU1");
    var listing = listingService.create(org, product, "W", null, "w", price("10"));
    listingService.publish(org, listing.getId());
    listingService.unpublish(org, listing.getId()); // published_at is now NULL — the erased trace

    dsl.deleteFrom(ORG_MILESTONE).execute();
    runV77Backfill();

    assertNotNull(reachedAt(org, "CATALOGUED"), "the listing row itself is durable");
    assertNull(
        reachedAt(org, "PUBLISHED"),
        "no durable evidence of the launch — must stay unstamped, never synthesised");
    assertEquals(1, residualCount(), "the residual query must count exactly this org");
  }

  // ------------------------------------------------------------ transactional integrity

  /** A rolled-back order leaves no FIRST_ORDER (or FIRST_PAYMENT) row — never best-effort. */
  @Test
  void milestoneWriteIsInTheBusinessTxn() {
    UUID org = createActiveOrg("acme");
    UUID staff = createUser("cashier@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 2);

    assertThrows(
        InsufficientStockException.class,
        () ->
            salesOrderService.placeInStoreSale(
                org,
                null,
                List.of(new SalesOrderService.OrderLineInput(product, 5)),
                new SalesOrderService.PaymentInput(PaymentProvider.CASH, null, null),
                null,
                actor(staff),
                UUID.randomUUID().toString(),
                staff));

    assertEquals(0, dsl.fetchCount(SALES_ORDER), "fixture control: the order rolled back");
    assertNull(reachedAt(org, "FIRST_ORDER"), "a rolled-back order left a FIRST_ORDER stamp");
    assertNull(reachedAt(org, "FIRST_PAYMENT"), "a rolled-back order left a FIRST_PAYMENT stamp");
  }

  @Test
  void deletingAnOrgCascadesItsMilestones() {
    UUID org = createActiveOrg("doomed");
    assertEquals(1, milestoneCount(org, "REGISTERED") + milestoneCount(org, "ACTIVATED"));

    dsl.deleteFrom(ORG).where(ORG.ID.eq(org)).execute();

    assertEquals(
        0,
        dsl.fetchCount(dsl.selectFrom(ORG_MILESTONE).where(ORG_MILESTONE.ORG_ID.eq(org))),
        "a deleted org's milestones are not a historical record of anything");
  }

  // ------------------------------------------------------------ the open-text guarantee

  /** A milestone the enum does not know is stored and simply not rendered. */
  @Test
  void unknownMilestoneIsStoredAndNotRendered() {
    UUID org = createActiveOrg("acme");
    dsl.insertInto(ORG_MILESTONE)
        .set(ORG_MILESTONE.ORG_ID, org)
        .set(ORG_MILESTONE.MILESTONE, "FIRST_EXPORT_V9")
        .set(ORG_MILESTONE.REACHED_AT, OffsetDateTime.now(ZoneOffset.UTC))
        .execute();

    JsonNode stages = funnel("?cohort=all&path=all").path("stages");

    assertEquals(6, stages.size(), "an unknown milestone must not add a seventh stage");
    for (JsonNode stage : stages) {
      assertTrue(!"FIRST_EXPORT_V9".equals(stage.path("stage").asText()));
    }
    assertEquals(1, milestoneCount(org, "FIRST_EXPORT_V9"), "…but it is stored");
  }

  // ------------------------------------------------------------ contract

  @Test
  void unknownCohort_is400() {
    Resp resp = invoke("GET", platform(SystemRole.ADMIN), "", "?cohort=7d&path=all");
    assertEquals(400, resp.status);
    assertTrue(bodyOf(resp).path("message").asText().contains("30d"), "the 400 must name options");
  }

  @Test
  void unknownPath_is400() {
    Resp resp = invoke("GET", platform(SystemRole.ADMIN), "", "?cohort=30d&path=organic");
    assertEquals(400, resp.status);
    assertTrue(bodyOf(resp).path("message").asText().contains("self_serve"));
  }

  @Test
  void post_is405() {
    assertEquals(
        405, invoke("POST", platform(SystemRole.ADMIN), "", "?cohort=all&path=all").status);
  }

  @Test
  void subpath_is404() {
    assertEquals(
        404, invoke("GET", platform(SystemRole.ADMIN), "/stages", "?cohort=all&path=all").status);
  }

  @Test
  void support_reads200() {
    createActiveOrg("acme");
    JsonNode admin = read(platform(SystemRole.ADMIN), "?cohort=all&path=all");
    JsonNode support = read(platform(SystemRole.SUPPORT), "?cohort=all&path=all");
    // as_of differs between the two calls by construction; everything else must be identical.
    assertEquals(admin.path("stages"), support.path("stages"));
    assertEquals(admin.path("cohort").path("size"), support.path("cohort").path("size"));
  }

  @Test
  void orgOwner_is403() {
    UUID org = createActiveOrg("acme");
    assertEquals(403, invoke("GET", orgOwnerOf(org), "", "?cohort=all&path=all").status);
  }

  @Test
  void anonymous_is401() {
    assertEquals(401, invoke("GET", null, "", "?cohort=all&path=all").status);
  }

  /** {@code as_of} is on the wire — this page gets screenshotted into strategy conversations. */
  @Test
  void asOfIsOnTheResponse() {
    assertTrue(!funnel("?cohort=all&path=all").path("as_of").isMissingNode());
  }

  // ------------------------------------------------------------ fixtures

  /** An org born active with both birth milestones, as provisioning would leave it. */
  private UUID createActiveOrg(String slug) {
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug + "-" + id)
        .set(ORG.CREATED_AT, now)
        .execute();
    dsl.insertInto(ORG_MILESTONE)
        .set(ORG_MILESTONE.ORG_ID, id)
        .set(ORG_MILESTONE.MILESTONE, "REGISTERED")
        .set(ORG_MILESTONE.REACHED_AT, now)
        .onConflictDoNothing()
        .execute();
    return id;
  }

  private void backdateOrg(UUID orgId, int days) {
    OffsetDateTime then = OffsetDateTime.now(ZoneOffset.UTC).minusDays(days);
    dsl.update(ORG).set(ORG.CREATED_AT, then).where(ORG.ID.eq(orgId)).execute();
    dsl.update(ORG_MILESTONE)
        .set(ORG_MILESTONE.REACHED_AT, then)
        .where(ORG_MILESTONE.ORG_ID.eq(orgId).and(ORG_MILESTONE.MILESTONE.eq("REGISTERED")))
        .execute();
  }

  private UUID createUser(String email) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, id + "-" + email)
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, com.loai.inventory.repository.generated.enums.ActorType.USER)
        .execute();
    return id;
  }

  private UUID createProduct(UUID org, String sku) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, sku + " widget")
        .set(PRODUCT.SKU, sku + "-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .execute();
    return id;
  }

  private void createInventory(UUID org, UUID product, int stockQty) {
    dsl.execute(
        "INSERT INTO inventory (org_id, product_id, stock_qty, reserved_qty) VALUES (?,?,?,0)",
        org,
        product,
        stockQty);
  }

  private UUID soleOrgOf(UUID userId) {
    return dsl.select(com.loai.inventory.repository.generated.Tables.USER_ORG_ROLE.ORG_ID)
        .from(com.loai.inventory.repository.generated.Tables.USER_ORG_ROLE)
        .where(com.loai.inventory.repository.generated.Tables.USER_ORG_ROLE.USER_ID.eq(userId))
        .fetchOne(com.loai.inventory.repository.generated.Tables.USER_ORG_ROLE.ORG_ID);
  }

  private String verifyTokenFor(UUID userId) {
    return credentialTokenService.mint(
        dsl,
        userId,
        com.loai.inventory.domain.model.AppUserTokenPurpose.EMAIL_VERIFY,
        OffsetDateTime.now());
  }

  private OffsetDateTime ownerVerifiedAt(UUID userId) {
    return dsl.select(APP_USER.EMAIL_VERIFIED_AT)
        .from(APP_USER)
        .where(APP_USER.ID.eq(userId))
        .fetchOne(APP_USER.EMAIL_VERIFIED_AT);
  }

  private OffsetDateTime orgCreatedAt(UUID orgId) {
    return dsl.select(ORG.CREATED_AT).from(ORG).where(ORG.ID.eq(orgId)).fetchOne(ORG.CREATED_AT);
  }

  private OffsetDateTime reachedAt(UUID orgId, String milestone) {
    return dsl.select(ORG_MILESTONE.REACHED_AT)
        .from(ORG_MILESTONE)
        .where(ORG_MILESTONE.ORG_ID.eq(orgId).and(ORG_MILESTONE.MILESTONE.eq(milestone)))
        .fetchOne(ORG_MILESTONE.REACHED_AT);
  }

  private int milestoneCount(UUID orgId, String milestone) {
    return dsl.fetchCount(
        dsl.selectFrom(ORG_MILESTONE)
            .where(ORG_MILESTONE.ORG_ID.eq(orgId).and(ORG_MILESTONE.MILESTONE.eq(milestone))));
  }

  /** V77's backfill statements, verbatim, so the test exercises the migration's own SQL. */
  private void runV77Backfill() {
    dsl.execute(
        """
        INSERT INTO org_milestone (org_id, milestone, reached_at)
        SELECT id, 'REGISTERED', created_at FROM org
        ON CONFLICT (org_id, milestone) DO NOTHING
        """);
    dsl.execute(
        """
        INSERT INTO org_milestone (org_id, milestone, reached_at)
        SELECT o.id, 'ACTIVATED', o.created_at
          FROM org o
         WHERE EXISTS (
                 SELECT 1 FROM platform_audit pa
                  WHERE pa.org_id = o.id AND pa.action = 'ORG_CREATE'
               )
        ON CONFLICT (org_id, milestone) DO NOTHING
        """);
    dsl.execute(
        """
        INSERT INTO org_milestone (org_id, milestone, reached_at)
        SELECT o.id, 'ACTIVATED', v.reached_at
          FROM org o
          JOIN LATERAL (
                 SELECT MIN(u.email_verified_at) AS reached_at
                   FROM user_org_role r
                   JOIN app_user u ON u.id = r.user_id
                  WHERE r.org_id = o.id AND r.role = 'OWNER'
               ) v ON v.reached_at IS NOT NULL
         WHERE NOT EXISTS (
                 SELECT 1 FROM platform_audit pa
                  WHERE pa.org_id = o.id AND pa.action = 'ORG_CREATE'
               )
        ON CONFLICT (org_id, milestone) DO NOTHING
        """);
    dsl.execute(
        """
        INSERT INTO org_milestone (org_id, milestone, reached_at)
        SELECT org_id, 'CATALOGUED', MIN(created_at)
          FROM product_listing
         GROUP BY org_id
        ON CONFLICT (org_id, milestone) DO NOTHING
        """);
    dsl.execute(
        """
        INSERT INTO org_milestone (org_id, milestone, reached_at)
        SELECT org_id, 'PUBLISHED', MIN(published_at)
          FROM product_listing
         WHERE published_at IS NOT NULL
         GROUP BY org_id
        ON CONFLICT (org_id, milestone) DO NOTHING
        """);
    dsl.execute(
        """
        INSERT INTO org_milestone (org_id, milestone, reached_at)
        SELECT org_id, 'FIRST_ORDER', MIN(COALESCE(placed_at, created_at))
          FROM sales_order
         GROUP BY org_id
        ON CONFLICT (org_id, milestone) DO NOTHING
        """);
    dsl.execute(
        """
        INSERT INTO org_milestone (org_id, milestone, reached_at)
        SELECT org_id, 'FIRST_PAYMENT', MIN(received_at)
          FROM payment
         GROUP BY org_id
        ON CONFLICT (org_id, milestone) DO NOTHING
        """);
  }

  /** The counted-residual query from V77's header. */
  private int residualCount() {
    return dsl.fetchOne(
            """
            SELECT count(*) FROM org o
             WHERE (EXISTS (SELECT 1 FROM product_listing pl WHERE pl.org_id = o.id)
                    OR EXISTS (SELECT 1 FROM sales_order so WHERE so.org_id = o.id))
               AND NOT EXISTS (SELECT 1 FROM org_milestone om
                                WHERE om.org_id = o.id AND om.milestone = 'PUBLISHED')
            """)
        .get(0, Integer.class);
  }

  private static BigDecimal price(String v) {
    return new BigDecimal(v);
  }

  private static Environment env() {
    return new Environment(Instant.now(), "1.2.3.4", "junit");
  }

  private ActorContext actor(UUID userId) {
    return ActorContext.user(userId.toString());
  }

  private SecurityContext platformCtx(UUID actorId) {
    return new SecurityContext(
        actorId, ActorType.USER, Set.of(SystemRole.ADMIN), Map.of(), Set.of(), 0);
  }

  // ------------------------------------------------------------ plumbing

  private long reachedOf(JsonNode stages, String stage) {
    for (JsonNode s : stages) {
      if (stage.equals(s.path("stage").asText())) {
        return s.path("reached").asLong();
      }
    }
    throw new AssertionError("stage " + stage + " absent from " + stages);
  }

  private JsonNode funnel(String query) {
    return read(platform(SystemRole.ADMIN), query);
  }

  private JsonNode read(SecurityContext ctx, String query) {
    Resp resp = invoke("GET", ctx, "", query);
    assertEquals(200, resp.status, () -> "body was " + bodyOf(resp).toString());
    return bodyOf(resp);
  }

  private Resp invoke(String method, SecurityContext ctx, String remaining, String query) {
    try {
      Resp resp = new Resp();
      handler.handle(method, reqWith(ctx, query), resp.mock, remaining);
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

  private SecurityContext platform(SystemRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(role), Map.of(), Set.of(), 0);
  }

  private SecurityContext orgOwnerOf(UUID orgId) {
    return new SecurityContext(
        UUID.randomUUID(),
        ActorType.USER,
        Set.of(),
        Map.of(orgId, Set.of(OrgRole.OWNER)),
        Set.of(),
        0);
  }

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
