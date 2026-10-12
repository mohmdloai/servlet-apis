package com.loai.inventory.api.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.common.crypto.SecretBox;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.repository.AppUserMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgHealthRepositoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgPaymobConfigRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgWhatsAppConfigRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentIntentRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformAuditRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.service.OrgPaymobService;
import com.loai.inventory.service.OrgService;
import com.loai.inventory.service.OrgWhatsAppService;
import com.loai.inventory.service.auth.AuthMailer;
import com.loai.inventory.service.auth.CredentialTokenService;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.loai.inventory.service.platform.OrgStatusService;
import com.loai.inventory.service.platform.PlatformAuditService;
import com.loai.inventory.service.platform.PlatformOrgService;
import com.loai.inventory.service.storefront.StorefrontCacheNotifier;
import com.loai.inventory.service.storefront.StorefrontRevalidator.Scope;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;

/**
 * {@code stories/storefront_revalidate_hook.md}: every committed write that changes the public
 * profile fires exactly one {@code profile} revalidation for the right slug; a write that throws
 * fires none.
 */
@Testcontainers
class StorefrontRevalidateHookIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

  record Call(String slug, Set<Scope> scopes) {}

  static final List<Call> calls = new CopyOnWriteArrayList<>();

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static OrgPaymobService paymob;
  static OrgPaymobService paymobNoKey;
  static OrgWhatsAppService whatsApp;
  static OrgService orgService;
  static PlatformOrgService platform;

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

    OrgRepositoryFactoryImpl orgRepoFactory = new OrgRepositoryFactoryImpl();
    StorefrontCacheNotifier notifier =
        new StorefrontCacheNotifier(
            dsl, orgRepoFactory, (slug, scopes) -> calls.add(new Call(slug, scopes)));

    SecretBox box = SecretBox.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
    paymob =
        new OrgPaymobService(
            dsl,
            new OrgPaymobConfigRepositoryFactoryImpl(),
            new PaymentIntentRepositoryFactoryImpl(),
            box,
            notifier);
    paymobNoKey =
        new OrgPaymobService(
            dsl,
            new OrgPaymobConfigRepositoryFactoryImpl(),
            new PaymentIntentRepositoryFactoryImpl(),
            SecretBox.fromBase64Key(null),
            notifier);
    whatsApp =
        new OrgWhatsAppService(dsl, new OrgWhatsAppConfigRepositoryFactoryImpl(), box, notifier);
    orgService =
        new OrgService(dsl, orgRepoFactory, new UserRepositoryFactoryImpl(), null, notifier);

    JedisPool jedisPool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
    platform =
        new PlatformOrgService(
            dsl,
            orgRepoFactory,
            new UserRepositoryFactoryImpl(),
            new OrgHealthRepositoryImpl(dsl),
            new PlatformAuditService(dsl, new PlatformAuditRepositoryFactoryImpl()),
            new OrgStatusService(jedisPool, dsl, orgRepoFactory),
            new CredentialTokenService(
                dsl,
                new AppUserMagicTokenRepositoryFactoryImpl(),
                "http://localhost:8080",
                Duration.ofMinutes(120),
                Duration.ofDays(7),
                Duration.ofHours(48)),
            new AuthMailer(msg -> {}),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()),
            notifier);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void fresh() {
    dsl.execute(
        "TRUNCATE platform_audit, payment_intent, sales_order, org_paymob_config,"
            + " org_whatsapp_config, user_org_role, app_user, org RESTART IDENTITY CASCADE");
    calls.clear();
  }

  private static final List<Call> ONE_PROFILE_FOR_ACME =
      List.of(new Call("acme", Set.of(Scope.PROFILE)));

  @Test
  void paymobConnect_reconnect_disconnect_eachFireOnce() {
    UUID org = org("acme");
    org("other"); // a second tenant: the hook must name the right one

    paymob.connect(org, "pk", "sk", "hmac", "ak", 123, "EGYPT");
    assertEquals(ONE_PROFILE_FOR_ACME, calls);

    calls.clear();
    paymob.connect(org, "pk2", "sk2", "hmac2", "ak2", 456, "EGYPT");
    assertEquals(ONE_PROFILE_FOR_ACME, calls);

    calls.clear();
    paymob.disconnect(org);
    assertEquals(ONE_PROFILE_FOR_ACME, calls);
  }

  @Test
  void aRefusedPaymobConnect_firesNothing() {
    UUID org = org("acme");

    assertThrows(
        ValidationException.class, () -> paymob.connect(org, " ", "sk", "hmac", "ak", 1, "EGYPT"));
    assertThrows(
        ConflictException.class,
        () -> paymobNoKey.connect(org, "pk", "sk", "hmac", "ak", 1, "EGYPT"));
    // FK violation inside the transaction → rolled back.
    assertThrows(
        RuntimeException.class,
        () -> paymob.connect(UUID.randomUUID(), "pk", "sk", "hmac", "ak", 1, "EGYPT"));

    assertEquals(List.of(), calls);
  }

  @Test
  void whatsAppConnect_disable_disconnect_eachFireOnce() {
    UUID org = org("acme");

    whatsApp.connect(org, "WABA-1", "PHONE-1", "01000000000", "TOKEN");
    assertEquals(ONE_PROFILE_FOR_ACME, calls);

    calls.clear();
    whatsApp.setEnabled(org, false);
    assertEquals(ONE_PROFILE_FOR_ACME, calls);

    calls.clear();
    whatsApp.disconnect(org);
    assertEquals(ONE_PROFILE_FOR_ACME, calls);

    calls.clear();
    assertThrows(NotFoundException.class, () -> whatsApp.setEnabled(org, true)); // no config now
    assertEquals(List.of(), calls);
  }

  @Test
  void tenantUpdate_firesOnce_andARefusedOneFiresNothing() {
    UUID org = org("acme");

    orgService.update(
        org,
        "Acme Renamed",
        null,
        null,
        null,
        new OrgService.StorefrontBranding("#112233", "acme@instapay", null, null),
        null,
        null);
    assertEquals(ONE_PROFILE_FOR_ACME, calls);

    calls.clear();
    assertThrows(ValidationException.class, () -> orgService.update(org, " "));
    assertThrows(NotFoundException.class, () -> orgService.update(UUID.randomUUID(), "Ghost"));
    assertEquals(List.of(), calls);
  }

  @Test
  void consoleUpdate_suspend_reactivate_eachFireOnce() {
    UUID org = org("acme");

    platform.updateOrg(admin(), env(), org, "Acme Ltd", null, null, null, null, null);
    assertEquals(ONE_PROFILE_FOR_ACME, calls);

    calls.clear();
    platform.suspend(admin(), env(), org, "chargebacks");
    assertEquals(ONE_PROFILE_FOR_ACME, calls);

    calls.clear();
    platform.reactivate(admin(), env(), org);
    assertEquals(ONE_PROFILE_FOR_ACME, calls);
  }

  @Test
  void orgDelete_namesTheSlugThatDiedWithTheRow() {
    UUID org = org("acme");

    orgService.delete(org);

    assertEquals(ONE_PROFILE_FOR_ACME, calls);
  }

  private UUID org(String slug) {
    UUID id = UUID.randomUUID();
    dsl.execute("INSERT INTO org(id,name,slug,active) VALUES (?,?,?,?)", id, slug, slug, true);
    return id;
  }

  private SecurityContext admin() {
    UUID id = UUID.randomUUID(); // platform_audit.actor_id is a FK into app_user
    dsl.execute(
        "INSERT INTO app_user(id,email,password_hash,actor_type,active,token_version)"
            + " VALUES (?,?,?,?::actor_type,?,?)",
        id,
        "admin-" + id + "@x.io",
        "x",
        "USER",
        true,
        0);
    return new SecurityContext(id, ActorType.USER, Set.of(SystemRole.ADMIN), Map.of(), Set.of(), 0);
  }

  private Environment env() {
    return new Environment(Instant.now(), "1.2.3.4", "junit");
  }
}
