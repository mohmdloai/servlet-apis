package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.PublicPageResponse;
import com.loai.inventory.api.dto.PublicPageSummaryResponse;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.StorefrontPageRepositoryFactoryImpl;
import com.loai.inventory.service.StorefrontPageService;
import com.loai.inventory.service.StorefrontPageService.PageInput;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
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
 * The storefront text-page slice end-to-end (customization epic slice C4): admin upsert/delete →
 * the anonymous list/detail reads, the default-locale-required + 20k-cap validation, the newline
 * byte-round-trip (storage is inert), the 400-vs-404 distinction (unknown kind vs never-written
 * kind), cross-org isolation, and the whitelisted public shape. Drives {@link
 * StorefrontPageService} directly (it owns both sides — no catalog resolution to mock). See {@code
 * stories/storefront_pages.md}.
 */
@Testcontainers
class StorefrontPagesIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static StorefrontPageService service;
  static final ObjectMapper JSON = ObjectMapperProvider.build();

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
        new StorefrontPageService(
            dsl, new StorefrontPageRepositoryFactoryImpl(), new OrgRepositoryFactoryImpl());
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void fresh() {
    dsl.execute("TRUNCATE storefront_page, org RESTART IDENTITY CASCADE");
  }

  // ───────── AC1: upsert idempotency + validation ─────────

  @Test
  void putCreatesThenReplacesIdempotently() {
    UUID org = insertOrg("acme", "en", true);

    service.upsert(org, "about", new PageInput(null, "We are Acme."));
    // A second PUT replaces (not appends) and keeps a single row per (org, kind).
    service.upsert(org, "about", new PageInput(null, "We are the new Acme."));

    assertEquals(1, service.list(org).size());
    assertEquals("We are the new Acme.", service.publicPage("acme", "about").getBodyEn());
  }

  @Test
  void missingDefaultLocaleBody_is400() {
    UUID enOrg = insertOrg("acme", "en", true);
    // EN-default org given only an AR body → 400 (required default-locale copy absent).
    assertThrows(
        ValidationException.class,
        () -> service.upsert(enOrg, "about", new PageInput("عنا", null)));

    UUID arOrg = insertOrg("nile", "ar", true);
    // AR-default org may ship AR-only copy.
    assertEquals("عنا", service.upsert(arOrg, "about", new PageInput("عنا", null)).getBodyAr());
    // …but EN-only on the AR-default org → 400.
    assertThrows(
        ValidationException.class,
        () -> service.upsert(arOrg, "policies", new PageInput(null, "Policy")));
  }

  @Test
  void bodyOver20kChars_is400() {
    UUID org = insertOrg("acme", "en", true);
    String tooLong = "x".repeat(20_001);
    assertThrows(
        ValidationException.class,
        () -> service.upsert(org, "about", new PageInput(null, tooLong)));
    // Exactly at the cap is fine.
    String atCap = "x".repeat(20_000);
    assertEquals(atCap, service.upsert(org, "about", new PageInput(null, atCap)).getBodyEn());
  }

  @Test
  void unknownKind_is400_onEveryVerb() {
    UUID org = insertOrg("acme", "en", true);
    insertOrgWritesAbout(org);
    assertThrows(
        ValidationException.class,
        () -> service.upsert(org, "faq", new PageInput(null, "hi"))); // PUT
    assertThrows(ValidationException.class, () -> service.delete(org, "faq")); // DELETE
    assertThrows(
        ValidationException.class, () -> service.publicPage("acme", "faq")); // public detail
  }

  @Test
  void deleteRemoves_thenPublicRead404_deletingUnwrittenKind_is404() {
    UUID org = insertOrg("acme", "en", true);
    service.upsert(org, "about", new PageInput(null, "We are Acme."));
    service.delete(org, "about");

    assertTrue(service.list(org).isEmpty());
    // A valid-but-never-written kind → 404 (distinct from the unknown-kind 400 above).
    assertThrows(NotFoundException.class, () -> service.publicPage("acme", "about"));
    // Deleting an already-absent (valid) kind → 404.
    assertThrows(NotFoundException.class, () -> service.delete(org, "about"));
  }

  // ───────── AC2: list = existing kinds; detail = both bodies verbatim (newline round-trip)
  // ─────────

  @Test
  void publicList_returnsExactlyExistingKinds_withUpdatedAt() {
    UUID org = insertOrg("acme", "en", true);
    service.upsert(org, "about", new PageInput(null, "About"));
    // policies not written → not listed.

    List<PublicPageSummaryResponse> list =
        service.publicList("acme").stream().map(PublicPageSummaryResponse::from).toList();
    assertEquals(List.of("about"), list.stream().map(PublicPageSummaryResponse::getKind).toList());
    assertTrue(list.get(0).getUpdatedAt() != null);
  }

  @Test
  void detail_roundTripsMultilineBodyByteIdentical_bothLocales() {
    UUID org = insertOrg("acme", "ar", true);
    // Newlines, an HTML-looking token, and a URL — all inert, stored and returned verbatim.
    String ar = "من نحن\n\nنبيع أدوات المطبخ.\n<b>ليس وسمًا</b>\nhttps://example.com";
    String en = "Line 1\nLine 2\n\n<b>test</b> https://example.com";
    service.upsert(org, "about", new PageInput(ar, en));

    var page = service.publicPage("acme", "about");
    assertEquals(
        ar, page.getBodyAr(), "AR body must round-trip byte-identical (no HTML stripping)");
    assertEquals(en, page.getBodyEn(), "EN body must round-trip byte-identical");
  }

  // ───────── AC3: whitelist — no id/org_id, both bodies + kind + updated_at only ─────────

  @Test
  void publicDetail_isWhitelisted() throws Exception {
    UUID org = insertOrg("acme", "en", true);
    service.upsert(org, "policies", new PageInput("سياسة", "Returns within 14 days."));

    PublicPageResponse dto = PublicPageResponse.from(service.publicPage("acme", "policies"));
    String json = JSON.writeValueAsString(dto);

    assertTrue(json.contains("body_ar"), json);
    assertTrue(json.contains("body_en"), json);
    assertTrue(json.contains("\"kind\""), json);
    assertTrue(json.contains("updated_at"), json);
    for (String forbidden : new String[] {"\"id\"", "org_id"}) {
      assertFalse(json.contains(forbidden), "leaked: " + forbidden + " in " + json);
    }
  }

  @Test
  void unwrittenLocale_dropsFromJson() throws Exception {
    UUID org = insertOrg("acme", "ar", true);
    service.upsert(org, "about", new PageInput("عنا", null));
    String json =
        JSON.writeValueAsString(PublicPageResponse.from(service.publicPage("acme", "about")));
    assertTrue(json.contains("body_ar"), json);
    assertFalse(json.contains("body_en"), json); // NON_NULL omission — the client falls back
    // And the domain body_en is genuinely null (not an empty string).
    assertNull(service.publicPage("acme", "about").getBodyEn());
  }

  // ───────── AC3: opaque org 404 ─────────

  @Test
  void unknownOrInactiveOrg_is404_bothReads() {
    insertOrg("suspended", "en", false);
    assertThrows(NotFoundException.class, () -> service.publicList("suspended"));
    assertThrows(NotFoundException.class, () -> service.publicPage("suspended", "about"));
    assertThrows(NotFoundException.class, () -> service.publicList("ghost"));
    assertThrows(NotFoundException.class, () -> service.publicPage("ghost", "about"));
  }

  // ───────── AC4: cross-org isolation (UNIQUE (org_id, kind)) ─────────

  @Test
  void orgAWrite_neverAffectsOrgBRead() {
    UUID a = insertOrg("acme", "en", true);
    UUID b = insertOrg("nile", "en", true);
    service.upsert(a, "about", new PageInput(null, "Acme about"));

    // B has its own namespace: same kind, independent row (and B's about is absent → 404).
    assertThrows(NotFoundException.class, () -> service.publicPage("nile", "about"));
    service.upsert(b, "about", new PageInput(null, "Nile about"));
    assertEquals("Acme about", service.publicPage("acme", "about").getBodyEn());
    assertEquals("Nile about", service.publicPage("nile", "about").getBodyEn());
  }

  // ───────── fixtures ─────────

  private void insertOrgWritesAbout(UUID orgId) {
    service.upsert(orgId, "about", new PageInput(null, "About"));
  }

  private UUID insertOrg(String slug, String defaultLocale, boolean active) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug)
        .set(ORG.ACTIVE, active)
        .set(ORG.DEFAULT_LOCALE, defaultLocale)
        .execute();
    return id;
  }
}
