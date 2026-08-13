package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.service.OrgService;
import com.loai.inventory.service.StorefrontService;
import com.loai.inventory.service.StorefrontService.StorefrontProfileView;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
 * Slice C2 — the OWNER-plane merge-PUT of the three SEO metadata fields ({@code meta_title}, {@code
 * meta_description}, {@code og_image_object_key}) via {@link OrgService#update}, plus the og-image
 * presign, verified end-to-end against a real database. Merge semantics mirror V52 branding: null =
 * leave-unchanged, blank = clear. The text fields surface on the public profile; the og key never
 * does (the stable stream route IS the URL). Admin-plane parity ({@code PATCH /api/admin/orgs}) is
 * covered in {@code PlatformOrgServiceIT}; the stream itself in {@code PublicOgImageIT}. See {@code
 * stories/storefront_seo_metadata.md}.
 */
@Testcontainers
class OrgSeoMetadataIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static StorefrontService storefront;
  static OrgService orgService;
  static ObjectStorage storage;

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
            new com.loai.inventory.repository.StorefrontCrawlRepositoryFactoryImpl(),
            storage,
            null,
            null);
    orgService =
        new OrgService(
            dsl,
            new OrgRepositoryFactoryImpl(),
            new com.loai.inventory.repository.UserRepositoryFactoryImpl(),
            storage);
  }

  @AfterAll
  static void stopInfra() {
    if (storage != null) storage.close();
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void fresh() {
    dsl.execute("TRUNCATE org RESTART IDENTITY CASCADE");
  }

  @Test
  void ownerSetsSeoText_surfacesOnProfile() {
    UUID id = insertOrg("acme");

    orgService.update(
        id, "Acme", null, null, null, null, seo("Acme — Best Prices", "Shop now", null));

    StorefrontProfileView v = storefront.profile("acme");
    assertEquals("Acme — Best Prices", v.metaTitle());
    assertEquals("Shop now", v.metaDescription());
  }

  @Test
  void unsetSeoText_isNullOnProfile() {
    insertOrg("bare");
    StorefrontProfileView v = storefront.profile("bare");
    assertNull(v.metaTitle());
    assertNull(v.metaDescription());
  }

  @Test
  void nullFieldLeavesUnchanged_blankStringClears() {
    UUID id = insertOrg("acme");
    orgService.update(id, "Acme", null, null, null, null, seo("Title", "Desc", null));

    // A null on both text fields is leave-unchanged (edits only, say, the og key).
    orgService.update(id, "Acme", null, null, null, null, seo(null, null, null));
    StorefrontProfileView after1 = storefront.profile("acme");
    assertEquals("Title", after1.metaTitle());
    assertEquals("Desc", after1.metaDescription());

    // A blank string clears.
    orgService.update(id, "Acme", null, null, null, null, seo("", "", null));
    StorefrontProfileView after2 = storefront.profile("acme");
    assertNull(after2.metaTitle());
    assertNull(after2.metaDescription());
  }

  @Test
  void overLengthMetaTitle_is400() {
    UUID id = insertOrg("acme");
    String tooLong = "x".repeat(OrgService.MAX_META_TITLE + 1);
    assertThrows(
        ValidationException.class,
        () -> orgService.update(id, "Acme", null, null, null, null, seo(tooLong, null, null)));
    // Exactly at the cap passes.
    orgService.update(
        id, "Acme", null, null, null, null, seo("x".repeat(OrgService.MAX_META_TITLE), null, null));
  }

  @Test
  void overLengthMetaDescription_is400() {
    UUID id = insertOrg("acme");
    String tooLong = "y".repeat(OrgService.MAX_META_DESCRIPTION + 1);
    assertThrows(
        ValidationException.class,
        () -> orgService.update(id, "Acme", null, null, null, null, seo(null, tooLong, null)));
  }

  @Test
  void foreignOgImageKey_is400() {
    UUID id = insertOrg("acme");
    UUID other = UUID.randomUUID();
    assertThrows(
        ValidationException.class,
        () ->
            orgService.update(
                id, "Acme", null, null, null, null, seo(null, null, other + "/og/deadbeef.png")));
  }

  @Test
  void presignOgImage_mintsOgScopedKey_thenAttachSucceeds() {
    UUID id = insertOrg("acme");

    OrgService.LogoPresign presign = orgService.presignOgImageUpload(id, "share.png", "image/png");
    assertTrue(presign.objectKey().startsWith(id + "/og/"), presign.objectKey());
    assertTrue(presign.uploadUrl().startsWith("http"), "presigned PUT URL");

    // The minted key attaches via the OWNER update path (same {orgId}/og/ guard).
    orgService.update(id, "Acme", null, null, null, null, seo(null, null, presign.objectKey()));
    assertEquals(
        presign.objectKey(),
        dsl.select(ORG.OG_IMAGE_OBJECT_KEY)
            .from(ORG)
            .where(ORG.ID.eq(id))
            .fetchOne(ORG.OG_IMAGE_OBJECT_KEY));
  }

  private static OrgService.SeoMetadata seo(String title, String desc, String ogKey) {
    return new OrgService.SeoMetadata(title, desc, ogKey);
  }

  private UUID insertOrg(String slug) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, "Acme")
        .set(ORG.SLUG, slug)
        .set(ORG.ACTIVE, true)
        .execute();
    return id;
  }
}
