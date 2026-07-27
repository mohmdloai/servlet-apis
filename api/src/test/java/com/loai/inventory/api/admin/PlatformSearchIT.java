package com.loai.inventory.api.admin;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.servlet.handler.SearchAdminHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.PlatformSearchType;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.repository.PlatformSearchRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.PaymentDirection;
import com.loai.inventory.repository.generated.enums.PaymentProvider;
import com.loai.inventory.repository.generated.enums.PaymentReconciliationStatus;
import com.loai.inventory.repository.generated.enums.PaymentVerificationStatus;
import com.loai.inventory.service.platform.PlatformSearchQuery;
import com.loai.inventory.service.platform.PlatformSearchService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
 * Integration coverage for {@code GET /api/admin/search?q=} ({@code stories/platform_search.md}) —
 * the cross-org lookup by an identifier a customer quoted.
 *
 * <p><strong>Every fixture seeds two orgs.</strong> That is not tidiness: the load-bearing claim of
 * this whole slice is that {@code order_number} and {@code customer.email} are unique <em>per
 * org</em>, so the same value legitimately exists in several tenants and the response must be a
 * list. A single-org fixture cannot fail that way, which is exactly why the original pitch's "jump
 * to the entity" survived until someone read the schema.
 *
 * <p>The handler is driven directly (rather than through Tomcat) so the authz matrix and the
 * 400/404/405 contract are exercised against the real service and a real database.
 */
@Testcontainers
class PlatformSearchIT {

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

  /** The bait for {@link #results_carryNoCustomerPii}. None of these may reach the wire. */
  private static final String CUSTOMER_NAME = "Nadia Abdelrahman";

  private static final String CUSTOMER_PHONE = "+201005550123";
  private static final String CUSTOMER_ADDRESS = "17 Sharia El-Nil, Zamalek, Cairo";
  private static final String CUSTOMER_NOTE_ON_ORDER = "leave with the bawab";

  /** The colliding identifiers every fixture plants in <em>both</em> tenants. */
  private static final String SHARED_ORDER_NUMBER = "SO-2026-00042";

  private static final String SHARED_CUSTOMER_EMAIL = "nadia@example.com";

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ObjectMapper mapper;

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
        "TRUNCATE payment_transaction, sales_order, customer, app_user, org RESTART IDENTITY"
            + " CASCADE");
  }

  // ------------------------------------------------------------------ the story's whole point

  /**
   * <strong>The load-bearing test.</strong> {@code UNIQUE (org_id, order_number)} means the
   * allocator in every tenant mints from the same counter, so {@code SO-2026-00042} exists in all
   * of them at once. The response is a list naming each tenant; a UI that jumped to the first would
   * silently open the wrong merchant's order.
   */
  @Test
  void sameOrderNumberInTwoOrgs_returnsBoth() {
    Orgs orgs = seedTwoTenants();

    JsonNode group = groupOf(search(SHARED_ORDER_NUMBER), PlatformSearchType.SALES_ORDER);

    assertEquals(2, group.path("total").asLong());
    assertEquals(2, group.path("results").size());
    assertEquals(Set.of("acme", "beta"), orgSlugs(group));
    group
        .path("results")
        .forEach(
            r -> {
              assertEquals(SHARED_ORDER_NUMBER, r.path("label").asText());
              assertNotNull(r.path("org").path("id").asText());
            });
    assertEquals(2, distinctOrgIds(group).size(), "both results named the same tenant");
    assertTrue(distinctOrgIds(group).containsAll(Set.of(orgs.acme, orgs.beta)));
  }

  /**
   * The same shape one table over — {@code customer.email} is {@code UNIQUE (org_id, email)} since
   * V15 dropped V2's global unique, so one person shopping at three merchants is three rows.
   *
   * <p>This is also <strong>the slice's one deliberate cross-tenant disclosure</strong>, asserted
   * on purpose: searching an email reveals which merchants that person shops at. Not new capability
   * (an ADMIN could always find it), but newly one keystroke — pinned so removing it later is a
   * visible decision, exactly as {@code to_address} was pinned in slice 2.
   */
  @Test
  void sameCustomerEmailInTwoOrgs_returnsBoth() {
    seedTwoTenants();

    JsonNode group = groupOf(search(SHARED_CUSTOMER_EMAIL), PlatformSearchType.CUSTOMER);

    assertEquals(2, group.path("total").asLong());
    assertEquals(Set.of("acme", "beta"), orgSlugs(group));
  }

  // ------------------------------------------------------------------ the genuinely unique three

  /** {@code UNIQUE (provider, provider_ref)} — globally unique, so exactly one, in one tenant. */
  @Test
  void providerRef_isGloballyUniqueAndReturnsOne() {
    Orgs orgs = seedTwoTenants();

    JsonNode group = groupOf(search("IPN-ACME-001"), PlatformSearchType.PAYMENT_TRANSACTION);

    assertEquals(1, group.path("total").asLong());
    JsonNode row = group.path("results").get(0);
    assertEquals("IPN-ACME-001", row.path("label").asText());
    assertEquals(orgs.acme.toString(), row.path("org").path("id").asText());
    assertEquals("INSTAPAY_MANUAL", row.path("sublabel").asText());
  }

  /** {@code UNIQUE (email)} on {@code app_user} — the one email that is global. No tenant block. */
  @Test
  void userEmail_returnsOne() {
    seedTwoTenants();

    JsonNode group = groupOf(search("staff@platform.test"), PlatformSearchType.APP_USER);

    assertEquals(1, group.path("total").asLong());
    JsonNode row = group.path("results").get(0);
    assertEquals("staff@platform.test", row.path("label").asText());
    assertEquals("Sara Fahmy", row.path("sublabel").asText());
    assertFalse(row.has("org"), "an app_user has no owning tenant and must not claim one");
  }

  /** {@code org.slug} is globally unique; an org result likewise carries no owning tenant. */
  @Test
  void orgSlug_returnsOne() {
    Orgs orgs = seedTwoTenants();

    JsonNode group = groupOf(search("acme"), PlatformSearchType.ORG);

    assertEquals(1, group.path("total").asLong());
    JsonNode row = group.path("results").get(0);
    assertEquals(orgs.acme.toString(), row.path("id").asText());
    assertEquals("acme", row.path("sublabel").asText());
    assertFalse(row.has("org"), "an org IS the tenant and must not nest one");
  }

  /**
   * An exact slug hit sorts ahead of a fuzzy name hit, because an exact identifier is unambiguous
   * and a name match is a guess. Seeded so the name match would otherwise sort first.
   */
  @Test
  void exactSlugSortsBeforeFuzzyNameMatches() {
    seedTwoTenants();
    // Its NAME contains "acme"; the slug "acme" belongs to the older org seeded first.
    createOrg("acme-supplies", "Acme Supplies Cairo");

    JsonNode group = groupOf(search("acme"), PlatformSearchType.ORG);

    assertEquals(2, group.path("total").asLong());
    assertEquals(
        "acme",
        group.path("results").get(0).path("sublabel").asText(),
        "the exact slug match did not sort first");
  }

  // ------------------------------------------------------------------ tenancy on the row

  /**
   * A suspended merchant's order is the one an operator is most likely to be asked about, and the
   * one nobody on the org side is working. Nothing filters on org status; the badge is the answer.
   */
  @Test
  void suspendedOrgIsSearchable() {
    Orgs orgs = seedTwoTenants();
    long before =
        groupOf(search(SHARED_ORDER_NUMBER), PlatformSearchType.SALES_ORDER).path("total").asLong();

    dsl.update(ORG)
        .set(ORG.ACTIVE, false)
        .set(ORG.SUSPENDED_AT, now())
        .set(ORG.SUSPENDED_REASON, "chargeback fraud")
        .where(ORG.ID.eq(orgs.beta))
        .execute();

    JsonNode group = groupOf(search(SHARED_ORDER_NUMBER), PlatformSearchType.SALES_ORDER);
    assertEquals(before, group.path("total").asLong(), "suspension must not shrink a result set");

    Map<String, String> statusBySlug = new java.util.HashMap<>();
    group
        .path("results")
        .forEach(
            r ->
                statusBySlug.put(
                    r.path("org").path("slug").asText(), r.path("org").path("status").asText()));
    assertEquals("suspended", statusBySlug.get("beta"));
    // …and its neighbour is unaffected: the status is per-row, not a page-level flag.
    assertEquals("active", statusBySlug.get("acme"));
  }

  /**
   * A tenant that has never been activated is {@code pending}, not {@code suspended} — slice 1.5's
   * partition, consumed here rather than re-derived from the {@code active} boolean.
   */
  @Test
  void pendingTenantReportsPending_notSuspended() {
    Orgs orgs = seedTwoTenants();
    dsl.update(ORG).set(ORG.ACTIVE, false).where(ORG.ID.eq(orgs.beta)).execute();

    JsonNode group = groupOf(search(SHARED_ORDER_NUMBER), PlatformSearchType.SALES_ORDER);
    boolean sawPending =
        stream(group.path("results"))
            .anyMatch(
                r ->
                    "beta".equals(r.path("org").path("slug").asText())
                        && "pending".equals(r.path("org").path("status").asText()));
    assertTrue(sawPending, "an un-suspended inactive tenant was not reported as pending");
  }

  // ------------------------------------------------------------------ the classifier, as behaviour

  /**
   * Asserted on results rather than internals, so it survives a refactor of the classifier: an
   * {@code @} query returns no {@code sales_order} group, and an {@code SO-} query returns no
   * {@code payment_transaction} group.
   */
  @Test
  void queryShapeSelectsProbes() {
    seedTwoTenants();

    Set<String> emailGroups = groupTypes(search(SHARED_CUSTOMER_EMAIL));
    assertTrue(emailGroups.contains("customer"));
    assertFalse(emailGroups.contains("sales_order"), "an email query probed sales_order");
    assertFalse(emailGroups.contains("payment_transaction"), "an email query probed transactions");
    assertFalse(emailGroups.contains("org"), "an email query probed org");

    Set<String> orderGroups = groupTypes(search(SHARED_ORDER_NUMBER));
    assertTrue(orderGroups.contains("sales_order"));
    assertFalse(orderGroups.contains("payment_transaction"), "an SO- query probed transactions");
    assertFalse(orderGroups.contains("customer"), "an SO- query probed customers");
  }

  /**
   * <strong>The exclusion, pinned.</strong> A customer whose name is exactly the query produces no
   * {@code customer} group — only their email finds them. Without this test the fuzzy-name probe
   * grows back in six months and takes 1706 ms per keystroke with it (pg_trgm extracts no trigram
   * below three characters, so a 2-char pattern rechecks the whole table through the GIN index).
   */
  @Test
  void customerNameIsNotSearchable() {
    seedTwoTenants();

    // The full name, a fragment, and the Arabic-folded form: none of them may find a customer.
    for (String probe : List.of(CUSTOMER_NAME, "Nadia", "Abdelrahman", "nadia")) {
      Set<String> types = groupTypes(search(probe));
      assertFalse(
          types.contains("customer"),
          "'" + probe + "' found a customer by name — the fuzzy-name probe has grown back");
    }
    // …and the email still does, so the absence above is the exclusion and not a broken fixture.
    assertTrue(groupTypes(search(SHARED_CUSTOMER_EMAIL)).contains("customer"));
  }

  // ------------------------------------------------------------------ normalization, as behaviour

  /** Arabic-Indic digits fold to ASCII: an operator pastes a reference out of a bank SMS. */
  @Test
  void arabicIndicDigitsInProviderRef_match() {
    seedTwoTenants();
    createTransaction(orgIdOf("acme"), "INSTA-01234");

    JsonNode group = groupOf(search("INSTA-٠١٢٣٤"), PlatformSearchType.PAYMENT_TRANSACTION);

    assertEquals(1, group.path("total").asLong());
    assertEquals("INSTA-01234", group.path("results").get(0).path("label").asText());
  }

  /** The same fold on an order number — and it happens before the {@code SO-} prefix test. */
  @Test
  void arabicIndicDigitsInOrderNumber_match() {
    seedTwoTenants();

    JsonNode group = groupOf(search("SO-٢٠٢٦-٠٠٠٤٢"), PlatformSearchType.SALES_ORDER);

    assertEquals(2, group.path("total").asLong(), "the digit fold did not reach both tenants");
  }

  /**
   * The deliberate divergence from the org-plane {@code ?order_number=} lookup, which is
   * case-sensitive. There, a merchant is checking their own record before committing money; here,
   * an operator is retyping what a customer emailed.
   */
  @Test
  void lowercaseOrderNumber_matches() {
    seedTwoTenants();
    assertEquals(
        2, groupOf(search("so-2026-00042"), PlatformSearchType.SALES_ORDER).path("total").asLong());
  }

  /**
   * The one place fuzzy matching survives this slice, and the reason it is safe: 200 rows. {@code
   * fold_search} runs on both sides, so {@code احمد} finds an org named {@code أحمد} — the alef
   * seats unify.
   */
  @Test
  void arabicOrgNameVariantsMatch() {
    seedTwoTenants();
    UUID id = createOrg("ahmed-store", "أحمد ستور");

    JsonNode group = groupOf(search("احمد"), PlatformSearchType.ORG);

    assertEquals(1, group.path("total").asLong());
    assertEquals(id.toString(), group.path("results").get(0).path("id").asText());
  }

  // ------------------------------------------------------------------ the whitelist

  /**
   * <strong>The whitelist pin</strong>, mirroring slice 2's. Every fixture customer has a name, a
   * phone and an address, and the order carries a customer note; none of those values may appear
   * anywhere in any response. Stronger than the story asked for: the customer <em>name</em> is
   * included in the bait even though it is not searchable, because the label is the email and a
   * name would be pure spill.
   */
  @Test
  void results_carryNoCustomerPii() {
    seedTwoTenants();

    for (String query :
        List.of(SHARED_ORDER_NUMBER, SHARED_CUSTOMER_EMAIL, "IPN-ACME-001", "acme")) {
      String json = search(query).toString();
      assertTrue(search(query).path("groups").size() > 0, query + ": nothing to inspect");
      for (String secret :
          List.of(CUSTOMER_NAME, CUSTOMER_PHONE, CUSTOMER_ADDRESS, CUSTOMER_NOTE_ON_ORDER)) {
        assertFalse(json.contains(secret), query + " leaked '" + secret + "'");
      }
    }
  }

  /**
   * A password hash is one {@code select()} star away from an operator's screen. It never crosses.
   */
  @Test
  void userResults_carryNoCredentials() {
    seedTwoTenants();

    String json = search("staff@platform.test").toString();

    assertFalse(json.contains("password"), "a credential field crossed");
    assertFalse(json.contains("$2a$"), "a bcrypt hash crossed");
    assertFalse(json.contains("token_version"), "the session counter crossed");
  }

  // ------------------------------------------------------------------ the cap and the count

  /**
   * <strong>A cap that lies about what it hid is the same defect as a truncating filter.</strong>
   * Seventeen tenants hold the same order number; five come back and {@code total} says 17, so the
   * client can render "showing 5 of 17" honestly.
   */
  @Test
  void capIsFive_andTotalTellsTheTruth() {
    for (int i = 0; i < 17; i++) {
      UUID org = createOrg("tenant-" + i, "Tenant " + i);
      createOrder(org, "SO-CAP-00001");
    }

    JsonNode group = groupOf(search("SO-CAP-00001"), PlatformSearchType.SALES_ORDER);

    assertEquals(5, group.path("results").size(), "the cap is not 5");
    assertEquals(17, group.path("total").asLong(), "total was results.length, not the true count");
    assertEquals(PlatformSearchQuery.GROUP_CAP, group.path("results").size());
  }

  /** A group with nothing in it is never sent — a client branches on presence, not on a zero. */
  @Test
  void emptyGroupsAreOmitted() {
    seedTwoTenants();

    JsonNode body = search(SHARED_ORDER_NUMBER);

    assertEquals(1, body.path("groups").size(), "an empty group was serialized");
    assertEquals("sales_order", body.path("groups").get(0).path("type").asText());
  }

  /** A query that matches nothing at all is a 200 with no groups, not a 404 and not an error. */
  @Test
  void nothingMatched_is200WithNoGroups() {
    seedTwoTenants();

    JsonNode body = read(platform(SystemRole.ADMIN), "", "?q=SO-NOPE-99999", 200);

    assertEquals(0, body.path("groups").size());
    assertEquals("SO-NOPE-99999", body.path("query").asText());
  }

  // ------------------------------------------------------------------ input contract

  @Test
  void blankQuery_is400() {
    assertEquals(400, invoke("GET", platform(SystemRole.ADMIN), "", "").status);
    assertEquals(400, invoke("GET", platform(SystemRole.ADMIN), "", "?q=").status);
    assertEquals(400, invoke("GET", platform(SystemRole.ADMIN), "", "?q=%20").status);
  }

  @Test
  void singleCharQuery_is400NamingTheMinimum() {
    Resp resp = invoke("GET", platform(SystemRole.ADMIN), "", "?q=a");
    assertEquals(400, resp.status);
    assertTrue(
        bodyOf(resp)
            .path("message")
            .asText()
            .contains(String.valueOf(PlatformSearchQuery.MIN_LENGTH)),
        "the 400 did not name the minimum");
  }

  @Test
  void post_is405() {
    assertEquals(405, invoke("POST", platform(SystemRole.ADMIN), "", "?q=acme").status);
    assertEquals(405, invoke("DELETE", platform(SystemRole.ADMIN), "", "?q=acme").status);
  }

  /**
   * <strong>404, not 400 — this is {@code OverviewAdminHandler}'s shape, not {@code
   * QueuesAdminHandler}'s.</strong> Queues 400s an unknown segment because its {@code {kind}} is an
   * enum value that happens to sit in the path; {@code /search} takes no path segment at all, so
   * anything after it is an unknown resource.
   */
  @Test
  void subpath_is404() {
    assertEquals(404, invoke("GET", platform(SystemRole.ADMIN), "/orders", "?q=acme").status);
    assertEquals(404, invoke("GET", platform(SystemRole.ADMIN), "/a/b", "?q=acme").status);
    // The bare route and its trailing slash are the endpoint itself, not a subpath.
    assertEquals(400, invoke("GET", platform(SystemRole.ADMIN), "/", "").status);
  }

  // ------------------------------------------------------------------ authz

  /** The whole resource is a read, so SUPPORT sees it — and sees exactly what ADMIN sees. */
  @Test
  void support_reads200() {
    seedTwoTenants();

    JsonNode support = read(platform(SystemRole.SUPPORT), "", "?q=" + SHARED_ORDER_NUMBER, 200);
    JsonNode admin = read(platform(SystemRole.ADMIN), "", "?q=" + SHARED_ORDER_NUMBER, 200);

    assertTrue(support.path("groups").size() > 0);
    assertEquals(admin, support, "nothing on this read is tier-gated");
  }

  /** An org OWNER holds no platform role, so the platform tier is not theirs to read. */
  @Test
  void orgOwner_is403() {
    assertEquals(403, invoke("GET", orgOwnerOnly(), "", "?q=acme").status);
  }

  @Test
  void anonymous_is401() {
    assertEquals(401, invoke("GET", null, "", "?q=acme").status);
  }

  /** Authorization is checked before the query is parsed, so a bad {@code q} is not an oracle. */
  @Test
  void authzPrecedesValidation() {
    assertEquals(401, invoke("GET", null, "", "").status);
    assertEquals(403, invoke("GET", orgOwnerOnly(), "", "?q=a").status);
  }

  // ------------------------------------------------------------------ fixtures

  /** The two tenants every fixture in this class seeds. */
  private record Orgs(UUID acme, UUID beta) {}

  /**
   * Two tenants, each holding the <em>same</em> order number and the <em>same</em> customer email,
   * plus near-misses so an assertion cannot pass by counting everything.
   */
  private Orgs seedTwoTenants() {
    UUID acme = createOrg("acme", "Acme Trading");
    UUID beta = createOrg("beta", "Beta Stores");

    for (UUID org : List.of(acme, beta)) {
      createCustomer(org, SHARED_CUSTOMER_EMAIL);
      createOrder(org, SHARED_ORDER_NUMBER);

      // …and the near-misses: a different customer and a different order in each tenant.
      createCustomer(org, "someone-" + seq.getAndIncrement() + "@example.com");
      createOrder(org, "SO-2026-9" + seq.getAndIncrement());
    }

    // Globally unique by construction — UNIQUE (provider, provider_ref).
    createTransaction(acme, "IPN-ACME-001");
    createTransaction(beta, "IPN-BETA-002");

    // A platform identity, which belongs to no tenant.
    createUser("staff@platform.test", "Sara Fahmy");
    createUser("other@platform.test", "Omar Zaki");

    return new Orgs(acme, beta);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private UUID createOrg(String slug, String name) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG).set(ORG.ID, id).set(ORG.NAME, name).set(ORG.SLUG, slug).execute();
    return id;
  }

  private UUID orgIdOf(String slug) {
    return dsl.select(ORG.ID).from(ORG).where(ORG.SLUG.eq(slug)).fetchOne(ORG.ID);
  }

  /**
   * A customer with every PII field populated — the bait for {@link #results_carryNoCustomerPii}.
   */
  private UUID createCustomer(UUID orgId, String email) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, orgId)
        .set(CUSTOMER.EMAIL, email)
        .set(CUSTOMER.NAME, CUSTOMER_NAME)
        .set(CUSTOMER.PHONE, CUSTOMER_PHONE)
        .set(CUSTOMER.ADDRESS, CUSTOMER_ADDRESS)
        .execute();
    return id;
  }

  private UUID createOrder(UUID orgId, String orderNumber) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, id)
        .set(SALES_ORDER.ORG_ID, orgId)
        .set(
            SALES_ORDER.CUSTOMER_ID,
            createCustomer(orgId, "buyer-" + seq.getAndIncrement() + "@e.test"))
        .set(SALES_ORDER.ORDER_NUMBER, orderNumber)
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, OrderStatus.PENDING_PAYMENT)
        .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal("250.00"))
        .set(SALES_ORDER.PLACED_AT, now().minusDays(3))
        .set(SALES_ORDER.NOTES, CUSTOMER_NOTE_ON_ORDER)
        .execute();
    return id;
  }

  private UUID createTransaction(UUID orgId, String providerRef) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.ID, id)
        .set(PAYMENT_TRANSACTION.ORG_ID, orgId)
        .set(PAYMENT_TRANSACTION.PROVIDER, PaymentProvider.instapay_manual)
        .set(PAYMENT_TRANSACTION.PROVIDER_REF, providerRef)
        .set(PAYMENT_TRANSACTION.DIRECTION, PaymentDirection.CREDIT)
        .set(PAYMENT_TRANSACTION.AMOUNT, new BigDecimal("100.00"))
        .set(PAYMENT_TRANSACTION.VERIFICATION_STATUS, PaymentVerificationStatus.VERIFIED)
        .set(PAYMENT_TRANSACTION.RECONCILIATION_STATUS, PaymentReconciliationStatus.ORPHAN)
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, now().minusDays(1))
        .set(PAYMENT_TRANSACTION.CUSTOMER_NOTE, CUSTOMER_NOTE_ON_ORDER)
        .set(
            PAYMENT_TRANSACTION.CLAIMED_BY_CUSTOMER_ID,
            createCustomer(orgId, "claimer-" + seq.getAndIncrement() + "@e.test"))
        .execute();
    return id;
  }

  private UUID createUser(String email, String displayName) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, email)
        .set(APP_USER.PASSWORD_HASH, "$2a$10$notarealhashjustfixturepadding000000000000000000000")
        .set(APP_USER.ACTOR_TYPE, com.loai.inventory.repository.generated.enums.ActorType.USER)
        .set(APP_USER.DISPLAY_NAME, displayName)
        .execute();
    return id;
  }

  // ------------------------------------------------------------------ plumbing

  private SearchAdminHandler handler() {
    return new SearchAdminHandler(
        new PlatformSearchService(dsl, new PlatformSearchRepositoryFactoryImpl()), mapper);
  }

  private JsonNode search(String q) {
    return read(platform(SystemRole.ADMIN), "", "?q=" + q, 200);
  }

  private JsonNode read(SecurityContext ctx, String remaining, String query, int expectedStatus) {
    Resp resp = invoke("GET", ctx, remaining, query);
    assertEquals(expectedStatus, resp.status, () -> "body was " + bodyOf(resp).toString());
    return bodyOf(resp);
  }

  /** The one group of {@code type}; fails loudly rather than returning a missing node. */
  private JsonNode groupOf(JsonNode body, PlatformSearchType type) {
    for (JsonNode group : body.path("groups")) {
      if (type.wire().equals(group.path("type").asText())) {
        return group;
      }
    }
    throw new AssertionError("no '" + type.wire() + "' group in " + body);
  }

  private Set<String> groupTypes(JsonNode body) {
    Set<String> types = new LinkedHashSet<>();
    body.path("groups").forEach(g -> types.add(g.path("type").asText()));
    return types;
  }

  private Set<String> orgSlugs(JsonNode group) {
    Set<String> slugs = new LinkedHashSet<>();
    group.path("results").forEach(r -> slugs.add(r.path("org").path("slug").asText()));
    return slugs;
  }

  private Set<UUID> distinctOrgIds(JsonNode group) {
    Set<UUID> ids = new LinkedHashSet<>();
    group.path("results").forEach(r -> ids.add(UUID.fromString(r.path("org").path("id").asText())));
    return ids;
  }

  private static java.util.stream.Stream<JsonNode> stream(JsonNode array) {
    List<JsonNode> nodes = new ArrayList<>();
    array.forEach(nodes::add);
    return nodes.stream();
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
          when(req.getParameter(pair.substring(0, eq)))
              .thenReturn(
                  java.net.URLDecoder.decode(
                      pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8));
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
