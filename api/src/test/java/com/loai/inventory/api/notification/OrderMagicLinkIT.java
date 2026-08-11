package com.loai.inventory.api.notification;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.repository.CustomerMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.MagicLinkService.ResolvedOrderView;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Order-scoped magic links (notifications-plan §7): minting returns a public URL whose raw token
 * resolves back to exactly one order, and only while live. Covers freshness, unknown/expired
 * rejection, and per-order isolation — the security-relevant logic behind the anonymous public
 * route.
 */
@Testcontainers
class OrderMagicLinkIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static MagicLinkService service;

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
    service =
        new MagicLinkService(
            dsl,
            new CustomerMagicTokenRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new CustomerRepositoryFactoryImpl(),
            "http://localhost:8080/",
            Duration.ofDays(30));
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void freshSchema() {
    dsl.execute("TRUNCATE customer_magic_token, customer, org RESTART IDENTITY CASCADE");
  }

  @Test
  void mint_thenResolve_returnsTheOrder() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org);
    UUID orderId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    MagicLinkService.OrderViewLink link =
        service.issueOrderViewLink(dsl, org, customer, orderId, now);
    String url = link.absolute();
    // The link points at the branded storefront status page /{locale}/{slug}/orders/{token} — NOT
    // the raw JSON endpoint. Trailing slash on the base URL is normalised; the token is the last
    // path segment. The org's default_locale drives the locale (DB default `ar`).
    assertTrue(
        url.matches("http://localhost:8080/(ar|en)/[^/]+/orders/[^/]+"),
        "unexpected order-view link: " + url);
    assertEquals(url.substring("http://localhost:8080".length()), link.relative());
    String rawToken = url.substring(url.lastIndexOf('/') + 1);

    Optional<ResolvedOrderView> resolved = service.resolveOrderView(rawToken, now);
    assertTrue(resolved.isPresent());
    assertEquals(org, resolved.get().orgId());
    assertEquals(orderId, resolved.get().orderId());
  }

  @Test
  void resolve_unknownToken_isEmpty() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    assertFalse(service.resolveOrderView("not-a-real-token", now).isPresent());
    assertFalse(service.resolveOrderView(null, now).isPresent());
  }

  @Test
  void resolve_expiredToken_isEmpty() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org);
    OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusDays(60);

    // Mint dated far in the past → already expired (TTL 30d).
    String url = service.issueOrderViewLink(dsl, org, customer, UUID.randomUUID(), past).absolute();
    String rawToken = url.substring(url.lastIndexOf('/') + 1);

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    assertFalse(service.resolveOrderView(rawToken, now).isPresent());
  }

  @Test
  void tokenForOrderA_neverResolvesToOrderB() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    UUID orderA = UUID.randomUUID();
    UUID orderB = UUID.randomUUID();

    String urlA = service.issueOrderViewLink(dsl, org, customer, orderA, now).absolute();
    String urlB = service.issueOrderViewLink(dsl, org, customer, orderB, now).absolute();
    String tokenA = urlA.substring(urlA.lastIndexOf('/') + 1);
    String tokenB = urlB.substring(urlB.lastIndexOf('/') + 1);

    assertEquals(orderA, service.resolveOrderView(tokenA, now).orElseThrow().orderId());
    assertEquals(orderB, service.resolveOrderView(tokenB, now).orElseThrow().orderId());
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

  private UUID createCustomer(UUID org) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, org)
        .set(CUSTOMER.NAME, "Nadia")
        .set(CUSTOMER.EMAIL, id + "@acme.test")
        .execute();
    return id;
  }
}
