package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.StorefrontProfileResponse;
import com.loai.inventory.common.exception.NotFoundException;
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
 * The public storefront profile ({@code stories/storefront_org_profile.md}, B1): an anonymous,
 * whitelisted per-org identity — name/slug/theme/locales/currency + payment instructions, no
 * internal field — settable by an OWNER and graceful for a pre-branding org. Drives {@link
 * StorefrontService#profile} and {@link OrgService#update} directly (presigning is offline).
 */
@Testcontainers
class StorefrontProfileIT {

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
    storefront =
        new StorefrontService(
            dsl,
            new OrgRepositoryFactoryImpl(),
            new com.loai.inventory.repository.ProductListingRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CategoryRepositoryFactoryImpl(),
            new com.loai.inventory.repository.InventoryRepositoryFactoryImpl(),
            new com.loai.inventory.repository.StorefrontBannerRepositoryFactoryImpl(),
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
  void activeOrg_returnsWhitelistedProfile_withDefaults() throws Exception {
    UUID id = insertOrg("acme", "Acme Store", true);

    StorefrontProfileView v = storefront.profile("acme");
    assertEquals("Acme Store", v.name());
    assertEquals("acme", v.slug());
    assertNull(v.logoUrl(), "no logo set → null");
    assertNull(v.themeColor());
    assertEquals("ar", v.defaultLocale(), "default landing locale is ar");
    assertEquals(java.util.List.of("ar", "en"), v.supportedLocales());
    assertEquals("EGP", v.currency());

    // No internal org field leaks in the serialized public DTO.
    String json = JSON.writeValueAsString(StorefrontProfileResponse.from(v));
    for (String forbidden :
        new String[] {
          "order_ttl_minutes",
          "refund_approval_threshold",
          "active",
          "created_at",
          "updated_at",
          id.toString()
        }) {
      assertFalse(json.contains(forbidden), "leaked: " + forbidden + " in " + json);
    }
  }

  @Test
  void unknownOrInactiveSlug_is404() {
    insertOrg("suspended", "Suspended", false);
    assertThrows(NotFoundException.class, () -> storefront.profile("suspended"));
    assertThrows(NotFoundException.class, () -> storefront.profile("ghost"));
  }

  @Test
  void ownerSetsBranding_visibleOnNextProfileRead() {
    UUID id = insertOrg("acme", "Acme Store", true);

    orgService.update(
        id,
        "Acme Store",
        null,
        null,
        null,
        new OrgService.StorefrontBranding(
            "#1E5AA8",
            "acme@instapay",
            "Send via InstaPay to acme@instapay, note your number.",
            "en"));

    StorefrontProfileView v = storefront.profile("acme");
    assertEquals("#1E5AA8", v.themeColor());
    assertEquals("acme@instapay", v.instapayHandle());
    assertTrue(v.paymentInstructions().contains("InstaPay"));
    assertEquals("en", v.defaultLocale());
  }

  @Test
  void invalidThemeColorOrLocale_is400() {
    UUID id = insertOrg("acme", "Acme Store", true);
    for (String badColor : new String[] {"bad", "#12", "#GGGGGG"}) {
      assertThrows(
          ValidationException.class,
          () ->
              orgService.update(
                  id,
                  "Acme Store",
                  null,
                  null,
                  null,
                  new OrgService.StorefrontBranding(badColor, null, null, null)));
    }
    assertThrows(
        ValidationException.class,
        () ->
            orgService.update(
                id,
                "Acme Store",
                null,
                null,
                null,
                new OrgService.StorefrontBranding(null, null, null, "fr")));
  }

  @Test
  void logoPresign_thenSetKey_profilePresignsGetUrl() {
    UUID id = insertOrg("acme", "Acme Store", true);

    OrgService.LogoPresign presign = orgService.presignLogoUpload(id, "logo.png", "image/png");
    assertTrue(presign.objectKey().startsWith(id + "/logo/"), presign.objectKey());
    assertTrue(presign.uploadUrl().startsWith("http"), "presigned PUT URL");

    // Attach the minted key via the OWNER update path.
    orgService.update(
        id,
        "Acme Store",
        null,
        null,
        new OrgService.BillingProfile(
            null, null, null, null, null, null, null, null, presign.objectKey()));

    StorefrontProfileView v = storefront.profile("acme");
    assertTrue(v.logoUrl() != null && v.logoUrl().startsWith("http"), "profile presigns a GET URL");

    // The admin-plane read (GET /api/orgs/{orgId}/logo) presigns the same key.
    String adminUrl = orgService.logoUrl(id);
    assertTrue(adminUrl != null && adminUrl.startsWith("http"), "admin read presigns a GET URL");
  }

  @Test
  void logoUrl_isNullWhenNoLogoIsSet() {
    UUID id = insertOrg("acme", "Acme Store", true);
    assertNull(orgService.logoUrl(id));
  }

  @Test
  void foreignLogoKey_is400() {
    UUID id = insertOrg("acme", "Acme Store", true);
    UUID other = UUID.randomUUID();
    assertThrows(
        ValidationException.class,
        () ->
            orgService.update(
                id,
                "Acme Store",
                null,
                null,
                new OrgService.BillingProfile(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    other + "/logo/deadbeef-logo.png")));
  }

  @Test
  void preMigrationOrg_servesGracefulProfile() {
    insertOrg("bare", "Bare Org", true);
    StorefrontProfileView v = storefront.profile("bare");
    assertNull(v.logoUrl());
    assertNull(v.themeColor());
    assertNull(v.instapayHandle());
    assertNull(v.paymentInstructions());
    assertEquals("ar", v.defaultLocale());
    // C2: unset SEO text fields are null (and omitted from JSON).
    assertNull(v.metaTitle());
    assertNull(v.metaDescription());
  }

  @Test
  void ownerSetsSeoMetadata_textSurfacesOnProfile_ogKeyDoesNot() throws Exception {
    UUID id = insertOrg("acme", "Acme Store", true);

    orgService.update(
        id,
        "Acme Store",
        null,
        null,
        null,
        null,
        new OrgService.SeoMetadata("Acme — Fair Prices", "Everyday essentials, delivered.", null));

    StorefrontProfileView v = storefront.profile("acme");
    assertEquals("Acme — Fair Prices", v.metaTitle());
    assertEquals("Everyday essentials, delivered.", v.metaDescription());

    // The public profile never carries an og-image URL — the stable route is the URL (C2, epic §6).
    String json = JSON.writeValueAsString(StorefrontProfileResponse.from(v));
    assertFalse(json.contains("og_image"), json);
  }

  private UUID insertOrg(String slug, String name, boolean active) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, name)
        .set(ORG.SLUG, slug)
        .set(ORG.ACTIVE, active)
        .execute();
    return id;
  }
}
