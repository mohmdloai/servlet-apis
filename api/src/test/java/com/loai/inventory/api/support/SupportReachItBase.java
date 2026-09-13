package com.loai.inventory.api.support;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PLATFORM_AUDIT;
import static com.loai.inventory.repository.generated.Tables.SUPPORT_TICKET;
import static com.loai.inventory.repository.generated.Tables.USER_ORG_ROLE;
import static com.loai.inventory.repository.generated.Tables.USER_SYSTEM_ROLE;

import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.domain.model.TicketCategory;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformAuditRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformTicketRepositoryFactoryImpl;
import com.loai.inventory.repository.SupportTicketRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.repository.generated.enums.OrgRole;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.SupportTicketService;
import com.loai.inventory.service.SupportTicketService.OpenCommand;
import com.loai.inventory.service.platform.PlatformAuditService;
import com.loai.inventory.service.platform.SupportDeskService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared infra for the slice-2 suites ({@code stories/support_ticket_reach.md}): the same wiring
 * {@link SupportTicketIT} builds, once, plus the fixtures every suite starts from — a desk of one
 * ADMIN and one SUPPORT user, and a tenant with a STAFF member. Each suite clears the cross-tenant
 * tables it reads, because the desk and the feed see every org.
 */
@Testcontainers
abstract class SupportReachItBase {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static NotificationService notifications;
  static SupportTicketService tickets;
  static SupportDeskService desk;

  UUID org;
  UUID staff;
  UUID admin;
  UUID support;

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

    notifications = TestWiring.notificationService(dsl);
    tickets =
        new SupportTicketService(
            dsl,
            new SupportTicketRepositoryFactoryImpl(),
            new UserRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            notifications,
            TestWiring.storage());
    desk =
        new SupportDeskService(
            dsl,
            new PlatformTicketRepositoryFactoryImpl(),
            new SupportTicketRepositoryFactoryImpl(),
            tickets,
            new PlatformAuditService(dsl, new PlatformAuditRepositoryFactoryImpl()));
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void seedDesk() {
    dsl.deleteFrom(NOTIFICATION).execute();
    dsl.deleteFrom(PLATFORM_AUDIT).execute();
    dsl.deleteFrom(SUPPORT_TICKET).execute();
    dsl.deleteFrom(USER_SYSTEM_ROLE).execute();
    org = createOrg("Mart Cairo");
    staff = member(org, OrgRole.STAFF, "Sara Hassan");
    admin = platformUser(SystemRole.ADMIN);
    support = platformUser(SystemRole.SUPPORT);
  }

  // helpers

  static OpenCommand cmd(String subject, String body) {
    return new OpenCommand(TicketCategory.ACCOUNT, subject, body, false, null, List.of());
  }

  static SecurityContext platform(UUID actor, SystemRole role) {
    return new SecurityContext(
        actor, com.loai.inventory.domain.model.ActorType.USER, Set.of(role), Map.of(), Set.of(), 0);
  }

  static Environment env() {
    return new Environment(Instant.now(), "127.0.0.1", "it");
  }

  UUID createOrg(String name) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, name)
        .set(ORG.SLUG, name.toLowerCase().replace(' ', '-') + "-" + id.toString().substring(0, 8))
        .set(ORG.DEFAULT_LOCALE, "en")
        .execute();
    return id;
  }

  UUID member(UUID orgId, OrgRole role, String displayName) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, id + "@mart-cairo.test")
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, ActorType.USER)
        .set(APP_USER.ACTIVE, true)
        .set(APP_USER.DISPLAY_NAME, displayName)
        .execute();
    dsl.insertInto(USER_ORG_ROLE)
        .set(USER_ORG_ROLE.USER_ID, id)
        .set(USER_ORG_ROLE.ORG_ID, orgId)
        .set(USER_ORG_ROLE.ROLE, role)
        .execute();
    return id;
  }

  UUID platformUser(SystemRole role) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, id + "@yabta3.test")
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, ActorType.USER)
        .set(APP_USER.ACTIVE, true)
        .execute();
    dsl.insertInto(USER_SYSTEM_ROLE)
        .set(USER_SYSTEM_ROLE.USER_ID, id)
        .set(
            USER_SYSTEM_ROLE.ROLE,
            com.loai.inventory.repository.generated.enums.SystemRole.lookupLiteral(role.name()))
        .execute();
    return id;
  }
}
