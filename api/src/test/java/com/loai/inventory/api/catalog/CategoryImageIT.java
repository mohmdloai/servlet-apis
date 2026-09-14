package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.domain.model.Category;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.CollectionRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgPaymobConfigRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgWhatsAppConfigRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.service.CategoryService;
import com.loai.inventory.service.CategoryService.CategoryView;
import com.loai.inventory.service.CategoryService.TranslatedNameInput;
import com.loai.inventory.service.ImagePresign;
import com.loai.inventory.service.StorefrontService;
import com.loai.inventory.service.StorefrontService.CategoryNav;
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
 * {@code stories/category_image.md} — the category image on the admin plane (presign mints an
 * org-prefixed key, the write-time prefix guard, create/update merge semantics, the presigned
 * preview on the read) and on the public nav ({@code image_url} per node, null when none).
 * Exercises {@link CategoryService} + {@link StorefrontService} directly; presigning is an offline
 * signature, so no object store is needed.
 */
@Testcontainers
class CategoryImageIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ObjectStorage storage;
  static CategoryService admin;
  static StorefrontService storefront;

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
    storage = TestWiring.storage();
    admin =
        new CategoryService(
            dsl, new CategoryRepositoryFactoryImpl(), new OrgRepositoryFactoryImpl(), storage);
    storefront =
        new StorefrontService(
            dsl,
            new OrgRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl(),
            new CategoryRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new com.loai.inventory.repository.StorefrontBannerRepositoryFactoryImpl(),
            new com.loai.inventory.repository.ListingReviewRepositoryFactoryImpl(),
            new CollectionRepositoryFactoryImpl(),
            new OrgWhatsAppConfigRepositoryFactoryImpl(),
            new OrgPaymobConfigRepositoryFactoryImpl(),
            new com.loai.inventory.repository.StorefrontCrawlRepositoryFactoryImpl(),
            storage,
            null,
            null);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void fresh() {
    dsl.execute("TRUNCATE category_translation, category, org RESTART IDENTITY CASCADE");
  }

  // AC1 + AC2: presign mints an org-prefixed key; a foreign or malformed key is a 400 at write
  // time.

  @Test
  void presign_mintsOrgPrefixedKey_andForeignKeyIs400() {
    Org o = org("acme");
    Org other = org("rival");

    ImagePresign presign = admin.presignImageUpload(o.id, "drinks.png", "image/png");
    assertTrue(presign.objectKey().startsWith(o.id + "/category/"), presign.objectKey());
    assertTrue(presign.uploadUrl().startsWith("http"));
    assertTrue(presign.expiresInSeconds() > 0);

    // Another org's key, a banner key of our own, and a bare filename are all rejected.
    for (String bad :
        List.of(
            other.id + "/category/x.png",
            ObjectStorage.bannerKeyPrefix(o.id) + "x.png",
            "drinks.png")) {
      assertThrows(
          ValidationException.class,
          () -> admin.create(o.id, "drinks", null, name("Drinks"), bad),
          bad);
    }
    assertThrows(
        ValidationException.class,
        () -> admin.presignImageUpload(o.id, " ", "image/png"),
        "blank filename");
  }

  // AC3: the key attaches on create, reads back with a presigned preview, and merges on update.

  @Test
  void create_withKey_readsBackPresigned_update_absentKeeps_blankClears_valueReplaces() {
    Org o = org("acme");
    String key = admin.presignImageUpload(o.id, "drinks.png", "image/png").objectKey();

    Category created = admin.create(o.id, "drinks", null, name("Drinks"), key);
    assertEquals(key, created.getImageObjectKey());

    CategoryView view = admin.getById(o.id, created.getId());
    assertEquals(key, view.category().getImageObjectKey());
    assertNotNull(view.imageUrl());
    assertTrue(view.imageUrl().startsWith("http"), view.imageUrl());
    // The list read carries it too.
    assertEquals(key, admin.getAll(o.id, 0, 10).get(0).category().getImageObjectKey());

    // Absent (the pre-image PUT shape) → unchanged.
    admin.update(o.id, created.getId(), "drinks", null, name("Drinks"));
    assertEquals(key, admin.getById(o.id, created.getId()).category().getImageObjectKey());

    // A new key → replaced.
    String key2 = admin.presignImageUpload(o.id, "drinks-2.png", "image/png").objectKey();
    admin.update(o.id, created.getId(), "drinks", null, name("Drinks"), key2);
    assertEquals(key2, admin.getById(o.id, created.getId()).category().getImageObjectKey());

    // Blank → cleared, and the preview URL goes with it.
    admin.update(o.id, created.getId(), "drinks", null, name("Drinks"), "  ");
    CategoryView cleared = admin.getById(o.id, created.getId());
    assertNull(cleared.category().getImageObjectKey());
    assertNull(cleared.imageUrl());
  }

  // AC4: the public nav carries image_url per node, null for image-less categories.

  @Test
  void publicNav_carriesPresignedImageUrl_nullWhenNone() {
    Org o = org("acme");
    String key = admin.presignImageUpload(o.id, "drinks.png", "image/png").objectKey();
    admin.create(o.id, "drinks", null, name("Drinks"), key);
    admin.create(o.id, "food", null, name("Food"), null);

    List<CategoryNav> nav = storefront.listCategories(o.slug);
    assertEquals(2, nav.size());
    CategoryNav drinks = nav.stream().filter(n -> n.slug().equals("drinks")).findFirst().get();
    CategoryNav food = nav.stream().filter(n -> n.slug().equals("food")).findFirst().get();
    assertNotNull(drinks.imageUrl());
    assertTrue(drinks.imageUrl().startsWith("http"), drinks.imageUrl());
    assertTrue(drinks.imageUrl().contains(key.substring(key.lastIndexOf('/') + 1)), "key in URL");
    assertNull(food.imageUrl());
  }

  // helpers

  private record Org(UUID id, String slug) {}

  private static TranslatedNameInput name(String name) {
    return new TranslatedNameInput(null, name);
  }

  private Org org(String slug) {
    UUID id = UUID.randomUUID();
    String orgSlug = slug + "-" + id.toString().substring(0, 8);
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, orgSlug)
        .set(ORG.ACTIVE, true)
        .execute();
    return new Org(id, orgSlug);
  }
}
