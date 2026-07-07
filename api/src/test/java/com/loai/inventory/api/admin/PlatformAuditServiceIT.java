package com.loai.inventory.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.PlatformAuditEntry;
import com.loai.inventory.domain.model.PlatformAuditEvent;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.repository.PlatformAuditRepositoryFactoryImpl;
import com.loai.inventory.service.platform.PlatformAuditService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end coverage of the platform audit-log read (PG2): newest-first ordering, pagination, the
 * {@code target_type} and {@code actor_id} filters, and that a written detail payload round-trips
 * back out. The read is the other half of the write ledger every platform mutation already leaves.
 */
@Testcontainers
class PlatformAuditServiceIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static PlatformAuditService service;

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

    service = new PlatformAuditService(dsl, new PlatformAuditRepositoryFactoryImpl());
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute("TRUNCATE platform_audit, app_user RESTART IDENTITY CASCADE");
  }

  private UUID user(String email) {
    UUID id = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO app_user(id,email,password_hash,actor_type,active,token_version)"
            + " VALUES (?,?,?,?::actor_type,?,?)",
        id,
        email,
        "x",
        "USER",
        true,
        0);
    return id;
  }

  private void auditRow(
      UUID actorId,
      String action,
      String targetType,
      UUID targetId,
      String detailJson,
      OffsetDateTime createdAt) {
    dsl.execute(
        "INSERT INTO platform_audit(id,actor_id,action,target_type,target_id,detail,created_at)"
            + " VALUES (?,?,?,?,?,?::jsonb,?::timestamptz)",
        UUID.randomUUID(),
        actorId,
        action,
        targetType,
        targetId,
        detailJson,
        createdAt.toString());
  }

  private SecurityContext admin(UUID id) {
    return new SecurityContext(id, ActorType.USER, Set.of(SystemRole.ADMIN), Map.of(), Set.of(), 0);
  }

  @Test
  void list_newestFirst_paginates() {
    UUID actor = user("admin@x.io");
    OffsetDateTime base = Instant.parse("2026-01-01T00:00:00Z").atOffset(ZoneOffset.UTC);
    auditRow(actor, "ORG_SUSPEND", "ORG", UUID.randomUUID(), null, base);
    auditRow(actor, "ORG_REACTIVATE", "ORG", UUID.randomUUID(), null, base.plusSeconds(10));
    auditRow(actor, "USER_DISABLE", "USER", UUID.randomUUID(), null, base.plusSeconds(20));

    PlatformAuditService.AuditPage first = service.list(0, 2, null, null);
    assertEquals(3, first.total());
    assertEquals(2, first.entries().size());
    assertEquals("USER_DISABLE", first.entries().get(0).action(), "newest first");
    assertEquals("ORG_REACTIVATE", first.entries().get(1).action());

    PlatformAuditService.AuditPage second = service.list(1, 2, null, null);
    assertEquals(1, second.entries().size());
    assertEquals("ORG_SUSPEND", second.entries().get(0).action(), "oldest last");
  }

  @Test
  void list_filtersByTargetType() {
    UUID actor = user("admin@x.io");
    OffsetDateTime t = Instant.parse("2026-01-01T00:00:00Z").atOffset(ZoneOffset.UTC);
    auditRow(actor, "ORG_SUSPEND", "ORG", UUID.randomUUID(), null, t);
    auditRow(actor, "USER_DISABLE", "USER", UUID.randomUUID(), null, t.plusSeconds(1));
    auditRow(actor, "USER_CREATE", "USER", UUID.randomUUID(), null, t.plusSeconds(2));

    PlatformAuditService.AuditPage users = service.list(0, 20, "USER", null);
    assertEquals(2, users.total());
    assertTrue(users.entries().stream().allMatch(e -> e.targetType().equals("USER")));

    assertEquals(1, service.list(0, 20, "ORG", null).total());
  }

  @Test
  void list_filtersByActor() {
    UUID a1 = user("a1@x.io");
    UUID a2 = user("a2@x.io");
    OffsetDateTime t = Instant.parse("2026-01-01T00:00:00Z").atOffset(ZoneOffset.UTC);
    auditRow(a1, "ORG_SUSPEND", "ORG", UUID.randomUUID(), null, t);
    auditRow(a1, "ORG_REACTIVATE", "ORG", UUID.randomUUID(), null, t.plusSeconds(1));
    auditRow(a2, "USER_DISABLE", "USER", UUID.randomUUID(), null, t.plusSeconds(2));

    PlatformAuditService.AuditPage byA1 = service.list(0, 20, null, a1);
    assertEquals(2, byA1.total());
    assertTrue(byA1.entries().stream().allMatch(e -> e.actorId().equals(a1)));
  }

  @Test
  void list_detailRoundTrips() {
    UUID actor = user("admin@x.io");
    UUID orgId = UUID.randomUUID();
    // Write through the real service path so the stored JSON matches production shape.
    service.record(
        admin(actor),
        new Environment(Instant.now(), "1.2.3.4", "junit"),
        "ORG_SUSPEND",
        PlatformAuditEvent.Target.ORG,
        orgId,
        Map.of("reason", "fraud investigation"));

    PlatformAuditService.AuditPage page = service.list(0, 20, null, null);
    assertEquals(1, page.total());
    PlatformAuditEntry entry = page.entries().get(0);
    assertEquals("ORG_SUSPEND", entry.action());
    assertEquals(orgId, entry.targetId());
    assertTrue(entry.detailJson().contains("fraud investigation"), "detail payload round-trips");
    assertEquals("1.2.3.4", entry.sourceIp());
  }
}
