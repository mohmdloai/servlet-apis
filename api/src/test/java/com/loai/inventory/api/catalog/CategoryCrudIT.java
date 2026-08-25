package com.loai.inventory.api.catalog;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Category;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.service.CategoryService;
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

/** Category hierarchy, slug-uniqueness, delete-with-children, and cross-org isolation. */
@Testcontainers
class CategoryCrudIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static CategoryService service;

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
        new CategoryService(
            dsl,
            new CategoryRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
            com.loai.inventory.api.support.TestWiring.storage());
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void fresh() {
    dsl.execute("TRUNCATE category, org RESTART IDENTITY CASCADE");
  }

  @Test
  void create_then_read() {
    UUID org = createOrg("acme");
    Category c = service.create(org, "Books", "books", null);
    Category read = service.getById(org, c.getId()).category();
    assertEquals("books", read.getSlug());
    assertEquals(1, service.count(org));
  }

  @Test
  void duplicateSlug_inSameOrg_conflicts() {
    UUID org = createOrg("acme");
    service.create(org, "Books", "books", null);
    assertThrows(ConflictException.class, () -> service.create(org, "Other", "books", null));
  }

  @Test
  void hierarchy_childPointsToParent() {
    UUID org = createOrg("acme");
    Category parent = service.create(org, "Notebooks", "notebooks", null);
    Category child = service.create(org, "Spiral", "spiral", parent.getId());
    assertEquals(
        parent.getId(), service.getById(org, child.getId()).category().getParentCategoryId());
  }

  @Test
  void parentNotInOrg_isRejected() {
    UUID orgA = createOrg("a");
    UUID orgB = createOrg("b");
    Category inB = service.create(orgB, "B", "b", null);
    assertThrows(ValidationException.class, () -> service.create(orgA, "X", "x", inB.getId()));
  }

  @Test
  void cyclicParent_isRejected() {
    UUID org = createOrg("acme");
    Category a = service.create(org, "A", "a", null);
    Category b = service.create(org, "B", "b", a.getId());
    // Trying to make A's parent be B closes the A→B→A loop.
    assertThrows(
        ValidationException.class, () -> service.update(org, a.getId(), "A", "a", b.getId()));
  }

  @Test
  void selfParent_isRejected() {
    UUID org = createOrg("acme");
    Category a = service.create(org, "A", "a", null);
    assertThrows(
        ValidationException.class, () -> service.update(org, a.getId(), "A", "a", a.getId()));
  }

  @Test
  void deleteWithChildren_isBlocked() {
    UUID org = createOrg("acme");
    Category parent = service.create(org, "P", "p", null);
    service.create(org, "C", "c", parent.getId());
    assertThrows(ConflictException.class, () -> service.delete(org, parent.getId()));
  }

  @Test
  void crossOrg_isolation() {
    UUID orgA = createOrg("a");
    UUID orgB = createOrg("b");
    Category inA = service.create(orgA, "A", "a", null);
    assertThrows(NotFoundException.class, () -> service.getById(orgB, inA.getId()));
    assertTrue(service.getAll(orgB, 0, 10).isEmpty());
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
}
