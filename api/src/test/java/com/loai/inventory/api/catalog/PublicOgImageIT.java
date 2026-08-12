package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.servlet.PublicStorefrontServlet;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.service.OgImageSource;
import com.loai.inventory.service.StorefrontService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
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
 * Slice C2 — the stable {@code GET /api/public/{orgSlug}/og-image} stream. Drives {@link
 * StorefrontService#ogImage} (fallback chain, 404s) with a deterministic fake {@link OgImageSource}
 * — no live object store needed, mirroring {@code DocumentRenderService.LogoSource}'s test seam —
 * and the servlet directly (reflection-injected) for the byte stream, content type, {@code
 * max-age=3600} cache header, and the 405 on non-GET. The stability property is asserted
 * structurally: the response is raw bytes (never a presigned URL that would expire), and the public
 * profile carries no og-image URL at all. See {@code stories/storefront_seo_metadata.md}.
 */
@Testcontainers
class PublicOgImageIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  /** A test double for {@link OgImageSource}: keyed bytes, empty for anything else (a miss/404). */
  static final class FakeOgImageSource implements OgImageSource {
    final Map<String, Fetched> byKey = new HashMap<>();

    @Override
    public Optional<Fetched> fetch(String objectKey) {
      return Optional.ofNullable(byKey.get(objectKey));
    }
  }

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ObjectStorage storage;
  static FakeOgImageSource source;
  static StorefrontService storefront;
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
    cfg.setMaximumPoolSize(4);
    cfg.setConnectionInitSql("SET search_path TO inventorydb");
    dataSource = new HikariDataSource(cfg);
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    storage = ObjectStorageFactory.build();
    source = new FakeOgImageSource();
    storefront =
        new StorefrontService(
            dsl,
            new OrgRepositoryFactoryImpl(),
            new com.loai.inventory.repository.ProductListingRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CategoryRepositoryFactoryImpl(),
            new com.loai.inventory.repository.InventoryRepositoryFactoryImpl(),
            new com.loai.inventory.repository.StorefrontBannerRepositoryFactoryImpl(),
            new com.loai.inventory.repository.ListingReviewRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CollectionRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgWhatsAppConfigRepositoryFactoryImpl(),
            storage,
            null,
            source);
  }

  @AfterAll
  static void stopInfra() {
    if (storage != null) storage.close();
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void fresh() {
    dsl.execute("TRUNCATE org RESTART IDENTITY CASCADE");
    source.byKey.clear();
  }

  @Test
  void ogKeySet_streamsThoseBytes() {
    UUID id = insertOrg("acme", true);
    String key = id + "/og/share.png";
    setOgKey(id, key);
    source.byKey.put(key, fetched("OG-BYTES", "image/png"));

    StorefrontService.OgImage img = storefront.ogImage("acme");
    assertArrayEquals("OG-BYTES".getBytes(StandardCharsets.UTF_8), img.bytes());
    assertEquals("image/png", img.contentType());
  }

  @Test
  void ogKeyUnset_fallsBackToLogoBytes() {
    UUID id = insertOrg("acme", true);
    String logoKey = id + "/logo/brand.jpg";
    setLogoKey(id, logoKey);
    source.byKey.put(logoKey, fetched("LOGO-BYTES", "image/jpeg"));

    StorefrontService.OgImage img = storefront.ogImage("acme");
    assertArrayEquals("LOGO-BYTES".getBytes(StandardCharsets.UTF_8), img.bytes());
    assertEquals("image/jpeg", img.contentType());
  }

  @Test
  void ogKeyPreferredOverLogo() {
    UUID id = insertOrg("acme", true);
    String ogKey = id + "/og/share.png";
    String logoKey = id + "/logo/brand.jpg";
    setOgKey(id, ogKey);
    setLogoKey(id, logoKey);
    source.byKey.put(ogKey, fetched("OG", "image/png"));
    source.byKey.put(logoKey, fetched("LOGO", "image/jpeg"));

    assertArrayEquals("OG".getBytes(StandardCharsets.UTF_8), storefront.ogImage("acme").bytes());
  }

  @Test
  void neitherKeySet_is404() {
    insertOrg("acme", true);
    assertThrows(NotFoundException.class, () -> storefront.ogImage("acme"));
  }

  @Test
  void storageMissOrError_is404_notAHang() {
    UUID id = insertOrg("acme", true);
    setOgKey(id, id + "/og/gone.png"); // key set, but the source has no bytes → miss
    assertThrows(NotFoundException.class, () -> storefront.ogImage("acme"));
  }

  @Test
  void unknownOrInactiveOrg_is404_opaque() {
    insertOrg("suspended", false);
    assertThrows(NotFoundException.class, () -> storefront.ogImage("suspended"));
    assertThrows(NotFoundException.class, () -> storefront.ogImage("ghost"));
  }

  // ───────── servlet slice: byte stream, content type, cache header, 405 ─────────

  @Test
  void servlet_streamsBytes_withContentType_andHourCacheHeader() throws Exception {
    UUID id = insertOrg("acme", true);
    String key = id + "/og/share.png";
    setOgKey(id, key);
    byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3};
    source.byKey.put(key, new OgImageSource.Fetched(png, "image/png"));

    Captured c = driveGet("/acme/og-image");
    verify(c.resp).setStatus(200);
    verify(c.resp).setContentType("image/png");
    verify(c.resp).setHeader("Cache-Control", "public, max-age=3600");
    assertArrayEquals(png, c.body.toByteArray());
  }

  @Test
  void servlet_neitherKey_is404() throws Exception {
    insertOrg("acme", true);
    Captured c = driveGet("/acme/og-image");
    verify(c.resp).setStatus(404);
  }

  @Test
  void servlet_nonGet_is405() throws Exception {
    insertOrg("acme", true);
    PublicStorefrontServlet servlet = servlet();
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("DELETE");
    when(req.getPathInfo()).thenReturn("/acme/og-image");
    Captured c = new Captured();
    c.resp = mockResp(c.body);
    servlet.service(req, c.resp);
    verify(c.resp).setStatus(405);
  }

  @Test
  void profile_carriesOgImageVersion_butNoKeyOrUrl() throws Exception {
    UUID id = insertOrg("acme", true);
    String ogKey = id + "/og/share.png";
    setOgKey(id, ogKey);

    var v1 = storefront.profile("acme");
    // A cache-busting version token is present — a short one-way hash embedding no key material.
    assertNotNull(v1.ogImageVersion());
    assertTrue(v1.ogImageVersion().matches("[0-9a-f]{12}"), v1.ogImageVersion());
    assertTrue(
        !v1.ogImageVersion().contains(ogKey) && !v1.ogImageVersion().contains("/og/"),
        "version must not embed the object key");

    String json =
        JSON.writeValueAsString(com.loai.inventory.api.dto.StorefrontProfileResponse.from(v1));
    // The version surfaces (so the storefront can ?v=-bust it); the raw object key, its /og/ path,
    // and any (expiring) presigned og URL never cross the wire — the stable GET .../og-image route
    // remains the URL (C2, epic §6). The profile's logo_url is a separate, legitimate display URL.
    assertTrue(json.contains("og_image_version"), json);
    for (String forbidden : new String[] {ogKey, "/og/"}) {
      assertTrue(!json.contains(forbidden), "leaked '" + forbidden + "' in profile: " + json);
    }

    // Attaching a DIFFERENT image changes the version → a new og:image URL → scrapers re-fetch.
    setOgKey(id, id + "/og/share-v2.png");
    assertNotEquals(v1.ogImageVersion(), storefront.profile("acme").ogImageVersion());
  }

  @Test
  void profile_ogImageVersion_fallsBackToLogo_nullWhenNeither() {
    insertOrg("bare", true);
    assertNull(storefront.profile("bare").ogImageVersion(), "no og key, no logo → no version");

    UUID logoOnly = insertOrg("logoonly", true);
    setLogoKey(logoOnly, logoOnly + "/logo/brand.png");
    // Same fallback chain as the streamed image: with only a logo, the logo keys the version.
    assertNotNull(storefront.profile("logoonly").ogImageVersion());
  }

  // ───────── helpers ─────────

  private static OgImageSource.Fetched fetched(String body, String contentType) {
    return new OgImageSource.Fetched(body.getBytes(StandardCharsets.UTF_8), contentType);
  }

  private static final class Captured {
    HttpServletResponse resp;
    final ByteArrayOutputStream body = new ByteArrayOutputStream();
  }

  private Captured driveGet(String pathInfo) throws Exception {
    PublicStorefrontServlet servlet = servlet();
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    when(req.getPathInfo()).thenReturn(pathInfo);
    Captured c = new Captured();
    c.resp = mockResp(c.body);
    servlet.service(req, c.resp);
    return c;
  }

  private static PublicStorefrontServlet servlet() throws Exception {
    PublicStorefrontServlet servlet = new PublicStorefrontServlet();
    inject(servlet, "service", storefront);
    inject(servlet, "mapper", JSON);
    return servlet;
  }

  private static HttpServletResponse mockResp(ByteArrayOutputStream body) throws Exception {
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.getOutputStream())
        .thenReturn(
            new ServletOutputStream() {
              @Override
              public void write(int b) {
                body.write(b);
              }

              @Override
              public void write(byte[] b, int off, int len) {
                body.write(b, off, len);
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setWriteListener(WriteListener listener) {}
            });
    return resp;
  }

  private static void inject(Object target, String field, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(field);
    f.setAccessible(true);
    f.set(target, value);
  }

  private void setOgKey(UUID id, String key) {
    dsl.update(ORG).set(ORG.OG_IMAGE_OBJECT_KEY, key).where(ORG.ID.eq(id)).execute();
  }

  private void setLogoKey(UUID id, String key) {
    dsl.update(ORG).set(ORG.LOGO_OBJECT_KEY, key).where(ORG.ID.eq(id)).execute();
  }

  private UUID insertOrg(String slug, boolean active) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, "Acme")
        .set(ORG.SLUG, slug)
        .set(ORG.ACTIVE, active)
        .execute();
    return id;
  }
}
