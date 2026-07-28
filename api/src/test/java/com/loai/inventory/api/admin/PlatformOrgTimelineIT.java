package com.loai.inventory.api.admin;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.IMPERSONATION_EVENT;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PLATFORM_AUDIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.servlet.handler.OrgAdminHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.PlatformAuditEvent;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.repository.OrgHealthRepositoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgTimelineRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformAuditRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.tables.records.AppUserRecord;
import com.loai.inventory.service.auth.AuthMailer;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.auth.CredentialTokenService;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.loai.inventory.service.platform.OrgStatusService;
import com.loai.inventory.service.platform.PlatformAuditService;
import com.loai.inventory.service.platform.PlatformOrgService;
import com.loai.inventory.service.platform.PlatformOrgTimelineService;
import com.loai.inventory.service.platform.UserAdminService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;

/**
 * Integration coverage for {@code GET /api/admin/orgs/{orgId}/timeline} ({@code
 * stories/platform_org_timeline.md}) — what has been done to this tenant, and by whom.
 *
 * <p><strong>Two orgs throughout.</strong> Every filter here has to be proven to filter rather than
 * to coincide: a single-tenant fixture passes just as happily when the {@code org_id} predicate is
 * missing entirely, which is the exact bug the slice exists to prevent.
 *
 * <p>Writes go through the <strong>real service call sites</strong> wherever the point is that the
 * call site passes an org ({@link #roleGrantAppearsOnTheOrgTimeline}, {@link
 * #provisionedOwnerCreationIsAttributed}) — asserting those against hand-inserted rows would test
 * the fixture rather than the code.
 */
@Testcontainers
class PlatformOrgTimelineIT {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  /** The one long free-text field on the page; the frontend must wrap it, never truncate it. */
  private static final String LONG_REASON =
      "Repeated chargebacks on InstaPay references that did not reconcile against any order, "
          + "reported by the acquiring bank on 2026-07-19 and confirmed by the merchant's own "
          + "statement export; suspended pending a full reconciliation of Q2.";

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ObjectMapper mapper;
  static PlatformOrgService orgService;
  static UserAdminService userAdminService;
  static PlatformAuditService audit;
  static PlatformOrgTimelineService timelineService;

  private final AtomicInteger seq = new AtomicInteger(1);

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
    mapper = ObjectMapperProvider.build();

    OrgRepositoryFactoryImpl orgRepoFactory = new OrgRepositoryFactoryImpl();
    UserRepositoryFactoryImpl userRepoFactory = new UserRepositoryFactoryImpl();
    JedisPool jedisPool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));

    audit = new PlatformAuditService(dsl, new PlatformAuditRepositoryFactoryImpl());
    OrgStatusService orgStatus = new OrgStatusService(jedisPool, dsl, orgRepoFactory);
    CredentialTokenService credentialTokenService =
        new CredentialTokenService(
            dsl,
            new com.loai.inventory.repository.AppUserMagicTokenRepositoryFactoryImpl(),
            "http://localhost:8080",
            Duration.ofMinutes(120),
            Duration.ofDays(7),
            Duration.ofHours(48));

    orgService =
        new PlatformOrgService(
            dsl,
            orgRepoFactory,
            userRepoFactory,
            new OrgHealthRepositoryImpl(dsl),
            audit,
            orgStatus,
            credentialTokenService,
            new AuthMailer(msg -> {}),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));

    // Only revokeOrgRole reaches AuthService (to propagate a de-privilege logout); a mock keeps
    // this IT off the session machinery, which is covered elsewhere.
    userAdminService =
        new UserAdminService(
            dsl,
            userRepoFactory,
            orgRepoFactory,
            Mockito.mock(AuthService.class),
            audit,
            credentialTokenService,
            new AuthMailer(msg -> {}));

    timelineService =
        new PlatformOrgTimelineService(dsl, new OrgTimelineRepositoryFactoryImpl(), orgRepoFactory);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void freshSchema() {
    dsl.execute(
        "TRUNCATE platform_audit, impersonation_event, user_org_role, app_user_magic_token,"
            + " app_user, org RESTART IDENTITY CASCADE");
  }

  // ------------------------------------------------------------------ the slice's whole point

  /**
   * <strong>The load-bearing test.</strong> Granting an org role writes {@code target_type = USER}
   * with the tenant buried in {@code detail}, so the pre-V76 predicate {@code target_type='ORG' AND
   * target_id=?} could not see it — a timeline built that way would answer "who was given access to
   * this tenant" with silence, which for an access-review question is the whole answer missing.
   *
   * <p>The control assertion at the bottom is what keeps this honest as the schema moves: it pins
   * that the row really is USER-targeted, so this test cannot start passing for the wrong reason if
   * someone later "simplifies" {@code ORG_ROLE_GRANT} to target the org.
   */
  @Test
  void roleGrantAppearsOnTheOrgTimeline() {
    Fixture f = twoTenants();
    UUID member = createUser("member@acme.test", "Hana Youssef");

    userAdminService.grantOrgRole(actorCtx(f.admin), env(), member, f.acme, OrgRole.MANAGER);

    JsonNode body = timeline(f.acme);
    JsonNode entry = entryWithAction(body, "ORG_ROLE_GRANT");
    assertEquals("audit", entry.path("source").asText());
    assertEquals("MANAGER", entry.path("detail").path("role").asText());
    assertEquals(f.acme.toString(), entry.path("detail").path("org_id").asText());

    // …and it is on the OTHER tenant's timeline nowhere.
    assertTrue(actionsOn(f.beta).isEmpty(), "a grant to acme surfaced on beta");

    // The control: this row targets the USER, which is precisely why V76 exists.
    String targetType =
        dsl.select(PLATFORM_AUDIT.TARGET_TYPE)
            .from(PLATFORM_AUDIT)
            .where(PLATFORM_AUDIT.ACTION.eq("ORG_ROLE_GRANT"))
            .fetchOne(PLATFORM_AUDIT.TARGET_TYPE);
    assertEquals(
        "USER",
        targetType,
        "ORG_ROLE_GRANT no longer targets the USER — the premise behind V76 has changed");
    assertEquals(
        0,
        dsl.fetchCount(
            dsl.selectFrom(PLATFORM_AUDIT)
                .where(PLATFORM_AUDIT.TARGET_TYPE.eq("ORG"))
                .and(PLATFORM_AUDIT.TARGET_ID.eq(f.acme))
                .and(PLATFORM_AUDIT.ACTION.eq("ORG_ROLE_GRANT"))),
        "the pre-V76 predicate would have found this row, so V76 was not needed");
  }

  /** The revoke half — the other side of an access review. */
  @Test
  void roleRevokeAppearsOnTheOrgTimeline() {
    Fixture f = twoTenants();
    UUID member = createUser("member@acme.test", "Hana Youssef");
    userAdminService.grantOrgRole(actorCtx(f.admin), env(), member, f.acme, OrgRole.MANAGER);
    userAdminService.revokeOrgRole(actorCtx(f.admin), env(), member, f.acme, OrgRole.MANAGER);

    assertTrue(actionsOn(f.acme).contains("ORG_ROLE_REVOKE"));
  }

  // ------------------------------------------------------------------ the provisioned owner

  /**
   * The forward half: from now on the write site carries the org, so the minted owner's {@code
   * USER_CREATE} lands on the tenant's timeline with no recovery needed.
   */
  @Test
  void provisionedOwnerCreationIsAttributed() {
    UUID admin = createUser("admin@platform.test", "Ops");
    PlatformOrgService.ProvisionResult result =
        orgService.provision(actorCtx(admin), env(), "Gamma Goods", "gamma", "owner@gamma.test");
    assertTrue(result.ownerMinted(), "fixture precondition: the owner had to be minted");

    List<String> actions = actionsOn(result.org().getId());
    assertTrue(actions.contains("ORG_CREATE"), "ORG_CREATE missing: " + actions);
    assertTrue(
        actions.contains("USER_CREATE"), "the minted owner's USER_CREATE was not attributed");

    JsonNode entry = entryWithAction(timeline(result.org().getId()), "USER_CREATE");
    assertEquals("org_provision", entry.path("detail").path("via").asText());
  }

  /**
   * The backward half — the migration's uniqueness argument, asserted rather than argued.
   *
   * <p>Two provisioning pairs are inserted in the <strong>pre-V76 shape</strong> ({@code org_id}
   * NULL) sharing one actor, plus a decoy {@code ORG_CREATE} naming the first owner again with
   * {@code owner_minted = false} — the row a second provisioning of an existing user writes. Then
   * V76's backfill statement runs verbatim.
   *
   * <p>Each {@code USER_CREATE} must land on its own org and the decoy must attract nothing. {@code
   * owner_minted = true} can appear at most once per user (a user is created once), so this is a
   * functional dependency the ledger already stores — which is why a {@code created_at} proximity
   * clause would add nothing and could only drop rows.
   */
  @Test
  void provisionedOwnerCreation_backfillJoinIsUnique() {
    Fixture f = twoTenants();
    UUID ownerA = createUser("owner-a@x.test", "Owner A");
    UUID ownerB = createUser("owner-b@x.test", "Owner B");

    // Pre-V76 shape: org_id NULL on every row, one shared actor, timestamps deliberately far apart
    // so a proximity clause would have broken this.
    insertLegacyAudit(f.admin, "ORG_CREATE", "ORG", f.acme, ownerMinted(ownerA, true), days(-400));
    insertLegacyAudit(f.admin, "USER_CREATE", "USER", ownerA, provisionDetail(), days(-1));
    insertLegacyAudit(f.admin, "ORG_CREATE", "ORG", f.beta, ownerMinted(ownerB, true), days(-300));
    insertLegacyAudit(f.admin, "USER_CREATE", "USER", ownerB, provisionDetail(), days(-2));
    // The decoy: ownerA provisioned as owner of a THIRD org, already existing, so no USER_CREATE.
    UUID third = createOrg("third", "Third Tenant");
    insertLegacyAudit(f.admin, "ORG_CREATE", "ORG", third, ownerMinted(ownerA, false), days(-10));

    runV76ProvisionedOwnerBackfill();

    assertEquals(
        f.acme, orgIdOfUserCreateFor(ownerA), "ownerA's USER_CREATE landed on the wrong org");
    assertEquals(
        f.beta, orgIdOfUserCreateFor(ownerB), "ownerB's USER_CREATE landed on the wrong org");
    assertEquals(
        0,
        dsl.fetchCount(
            dsl.selectFrom(PLATFORM_AUDIT)
                .where(PLATFORM_AUDIT.ORG_ID.eq(third))
                .and(PLATFORM_AUDIT.ACTION.eq("USER_CREATE"))),
        "the owner_minted=false decoy attracted a USER_CREATE");
  }

  // ------------------------------------------------------------------ the merge

  /**
   * Both ledgers, one stream, correctly ordered. The impersonation leg is the honest part: an
   * overlay produces <em>no</em> {@code platform_audit} rows at all, because the operator's actions
   * inside the tenant are written as the impersonated user — so a timeline built from the audit
   * ledger alone renders a period of intense support activity as total silence.
   */
  @Test
  void mergesBothLedgers_newestFirst() {
    Fixture f = twoTenants();
    UUID support = createUser("support@platform.test", "Mona Adel");

    insertAudit(f.admin, f.acme, "ORG_UPDATE", "ORG", f.acme, null, minutes(-30));
    insertImpersonation(support, f.member, "ORG", f.acme, "START", minutes(-20));
    insertAudit(f.admin, f.acme, "ORG_SUSPEND", "ORG", f.acme, reason(LONG_REASON), minutes(-10));

    JsonNode data = timeline(f.acme).path("data");

    assertEquals(3, data.size());
    assertEquals(List.of("ORG_SUSPEND", "IMPERSONATION_START", "ORG_UPDATE"), actionsIn(data));
    assertEquals(
        List.of("audit", "impersonation", "audit"),
        sourcesIn(data),
        "source must distinguish the two ledgers, not be inferred from the verb");
    assertTrue(newestFirst(data), "the merged stream was not ordered newest-first");
  }

  /** {@code created_at DESC, id DESC} must hold across a page boundary, not only within a page. */
  @Test
  void paging_isNewestFirstAcrossPageBoundary() {
    Fixture f = twoTenants();
    for (int i = 0; i < 7; i++) {
      insertAudit(f.admin, f.acme, "ORG_UPDATE", "ORG", f.acme, null, minutes(-i));
    }
    insertImpersonation(f.admin, f.member, "ORG", f.acme, "START", minutes(-3));

    JsonNode p0 =
        read(platform(SystemRole.ADMIN), "/" + f.acme + "/timeline", "?page=0&size=3", 200);
    JsonNode p1 =
        read(platform(SystemRole.ADMIN), "/" + f.acme + "/timeline", "?page=1&size=3", 200);
    JsonNode p2 =
        read(platform(SystemRole.ADMIN), "/" + f.acme + "/timeline", "?page=2&size=3", 200);

    assertEquals(8, p0.path("total").asLong(), "total must span both ledgers");
    assertEquals(3, p0.path("data").size());
    assertEquals(3, p1.path("data").size());
    assertEquals(2, p2.path("data").size());

    List<String> ids = new ArrayList<>();
    for (JsonNode page : List.of(p0, p1, p2)) {
      page.path("data").forEach(e -> ids.add(e.path("id").asText()));
    }
    assertEquals(
        8, new LinkedHashSet<>(ids).size(), "a row was duplicated or dropped across pages");

    List<JsonNode> all = new ArrayList<>();
    for (JsonNode page : List.of(p0, p1, p2)) {
      page.path("data").forEach(all::add);
    }
    assertTrue(newestFirstNodes(all), "ordering broke at a page boundary");
  }

  /**
   * A page whose rows all come from one source still pages correctly — the case an interleave-in-
   * Java implementation gets wrong first, because one source's cursor runs ahead of the other's.
   */
  @Test
  void pageDrawnEntirelyFromOneSource_isStillCorrect() {
    Fixture f = twoTenants();
    for (int i = 0; i < 5; i++) {
      insertAudit(f.admin, f.acme, "ORG_UPDATE", "ORG", f.acme, null, minutes(-i));
    }
    insertImpersonation(f.admin, f.member, "ORG", f.acme, "START", minutes(-90));

    JsonNode p0 =
        read(platform(SystemRole.ADMIN), "/" + f.acme + "/timeline", "?page=0&size=5", 200);
    JsonNode p1 =
        read(platform(SystemRole.ADMIN), "/" + f.acme + "/timeline", "?page=1&size=5", 200);

    assertEquals(Set.of("audit"), new LinkedHashSet<>(sourcesIn(p0.path("data"))));
    assertEquals(1, p1.path("data").size());
    assertEquals("impersonation", p1.path("data").get(0).path("source").asText());
  }

  // ------------------------------------------------------------------ what must NOT appear

  /**
   * {@code org_id IS NULL} means "not a tenant event". These four concern the identity, not the
   * merchant — a timeline that swept them in would claim the tenant was touched when it was not.
   */
  @Test
  void platformWideActionsAreAbsent() {
    Fixture f = twoTenants();
    // f.member really is a member of acme, so this is the tempting case, not a strawman.
    userAdminService.grantOrgRole(actorCtx(f.admin), env(), f.member, f.acme, OrgRole.STAFF);
    userAdminService.grantSystemRole(actorCtx(f.admin), env(), f.member, SystemRole.SUPPORT);
    audit.record(
        actorCtx(f.admin),
        env(),
        null,
        "FORCE_LOGOUT_ALL",
        PlatformAuditEvent.Target.USER,
        f.member,
        Map.of());
    audit.record(
        actorCtx(f.admin),
        env(),
        null,
        "SESSION_REVOKE",
        PlatformAuditEvent.Target.SESSION,
        UUID.randomUUID(),
        Map.of("user_id", f.member.toString()));

    List<String> actions = actionsOn(f.acme);

    assertEquals(List.of("ORG_ROLE_GRANT"), actions, "a platform-wide action leaked onto a tenant");
  }

  /** The second tenant's suspension never appears on the first's timeline. */
  @Test
  void otherOrgsEventsAreAbsent() {
    Fixture f = twoTenants();
    orgService.suspend(actorCtx(f.admin), env(), f.beta, "beta only");

    assertTrue(actionsOn(f.acme).isEmpty(), "beta's suspension surfaced on acme");
    assertEquals(List.of("ORG_SUSPEND"), actionsOn(f.beta));
  }

  /**
   * V41's {@code ck_imp_scope} makes {@code (tier='ORG') = (scope_org_id IS NOT NULL)} an
   * invariant, so a platform-tier overlay is about no tenant and is correctly absent.
   */
  @Test
  void platformTierImpersonationIsAbsent() {
    Fixture f = twoTenants();
    insertImpersonation(f.admin, f.member, "PLATFORM", null, "START", minutes(-5));

    assertTrue(actionsOn(f.acme).isEmpty());
    assertTrue(actionsOn(f.beta).isEmpty());
  }

  @Test
  void orgTierImpersonationIsPresent() {
    Fixture f = twoTenants();
    insertImpersonation(f.admin, f.member, "ORG", f.acme, "START", minutes(-6));
    insertImpersonation(f.admin, f.member, "ORG", f.acme, "STOP", minutes(-5));

    assertEquals(List.of("IMPERSONATION_STOP", "IMPERSONATION_START"), actionsOn(f.acme));
    assertTrue(actionsOn(f.beta).isEmpty(), "acme's overlay surfaced on beta");
  }

  /** The impersonated user is the payload; the actor is whoever put the overlay on. */
  @Test
  void impersonationCarriesItsTargetInDetail_andItsOperatorAsActor() {
    Fixture f = twoTenants();
    UUID support = createUser("support@platform.test", "Mona Adel");
    insertImpersonation(support, f.member, "ORG", f.acme, "START", minutes(-5));

    JsonNode entry = entryWithAction(timeline(f.acme), "IMPERSONATION_START");

    assertEquals(support.toString(), entry.path("actor").path("id").asText());
    assertEquals("support@platform.test", entry.path("actor").path("email").asText());
    assertEquals(f.member.toString(), entry.path("detail").path("target_id").asText());
  }

  // ------------------------------------------------------------------ open text, actors, status

  /**
   * {@code action} is a {@code VARCHAR(64)} with no enum behind it. A verb no Java code knows must
   * serialise rather than 500 — a Java enum here would be a second definition of a vocabulary the
   * database already owns, and would break on the first action someone adds without updating it.
   */
  @Test
  void unknownAction_passesThrough() {
    Fixture f = twoTenants();
    insertAudit(f.admin, f.acme, "ORG_QUARANTINE_V9", "ORG", f.acme, null, minutes(-1));

    JsonNode data = timeline(f.acme).path("data");

    assertEquals(1, data.size());
    assertEquals("ORG_QUARANTINE_V9", data.get(0).path("action").asText());
  }

  /** One query for the page's actors, and every entry names its own. */
  @Test
  void actorIsBatchLoadedAndNamed() {
    Fixture f = twoTenants();
    UUID second = createUser("second@platform.test", "Karim Nabil");
    insertAudit(f.admin, f.acme, "ORG_UPDATE", "ORG", f.acme, null, minutes(-3));
    insertAudit(second, f.acme, "ORG_SUSPEND", "ORG", f.acme, reason("x"), minutes(-2));

    JsonNode data = timeline(f.acme).path("data");

    Set<String> emails = new LinkedHashSet<>();
    data.forEach(e -> emails.add(e.path("actor").path("email").asText()));
    assertEquals(Set.of("second@platform.test", "admin@platform.test"), emails);
    data.forEach(e -> assertNotNull(e.path("actor").path("id").asText()));
  }

  /**
   * An actor whose {@code app_user} row is gone stays unresolved — the entry keeps its id and
   * carries no email. <strong>Never invent an actor.</strong>
   *
   * <p>Note honestly what this does and does not prove: {@code platform_audit.actor_id} is {@code
   * NOT NULL REFERENCES app_user(id)}, so on the real schema an actor <em>cannot</em> go missing,
   * and the test has to drop the constraint to reach the branch. So this pins the batch-loader's
   * defensive behaviour, not a reachable production state. The place a missing actor is genuinely
   * reachable is the client, which must render the raw identifier rather than the current operator
   * — story 78 owns that half.
   */
  @Test
  void missingActorIsNotInvented() {
    Fixture f = twoTenants();
    UUID ghost = createUser("ghost@platform.test", "Ghost");
    insertAudit(ghost, f.acme, "ORG_UPDATE", "ORG", f.acme, null, minutes(-1));
    // Drop the FK so the actor can genuinely vanish, then delete them.
    dsl.execute("ALTER TABLE platform_audit DROP CONSTRAINT platform_audit_actor_id_fkey");
    dsl.deleteFrom(APP_USER).where(APP_USER.ID.eq(ghost)).execute();
    try {
      JsonNode entry = timeline(f.acme).path("data").get(0);
      assertEquals(ghost.toString(), entry.path("actor").path("id").asText());
      assertTrue(
          entry.path("actor").path("email").isMissingNode(),
          "an unresolvable actor was given an email");
    } finally {
      // Clear the deliberately-orphaned rows before restoring the constraint they violate.
      dsl.deleteFrom(PLATFORM_AUDIT).execute();
      dsl.execute(
          "ALTER TABLE platform_audit ADD CONSTRAINT platform_audit_actor_id_fkey"
              + " FOREIGN KEY (actor_id) REFERENCES app_user(id)");
    }
  }

  /** Nothing filters on org status — same rule as the queues and search. */
  @Test
  void suspendedOrgHasATimeline() {
    Fixture f = twoTenants();
    orgService.suspend(actorCtx(f.admin), env(), f.acme, LONG_REASON);

    JsonNode body = timeline(f.acme);

    assertEquals(1, body.path("data").size());
    JsonNode entry = body.path("data").get(0);
    assertEquals("ORG_SUSPEND", entry.path("action").asText());
    assertEquals(
        LONG_REASON,
        entry.path("detail").path("reason").asText(),
        "the suspension reason must cross in full — this page exists to show it");
  }

  /**
   * The tenant's birth is on the <strong>envelope</strong>, not in the stream: self-serve
   * registration writes no audit row at all, so most tenants have no {@code ORG_CREATE} and the
   * oldest entry is routinely not the beginning. The client renders it as a terminal cap.
   */
  @Test
  void registrationAnchorIsOnTheEnvelope_andIsNeverAnEntry() {
    Fixture f = twoTenants();
    insertAudit(f.admin, f.acme, "ORG_UPDATE", "ORG", f.acme, null, minutes(-1));

    JsonNode body = timeline(f.acme);

    assertNotNull(body.path("created_at").asText(), "the envelope carries no created_at");
    assertFalse(body.path("created_at").isMissingNode());
    assertEquals(1, body.path("data").size(), "the anchor was synthesized into the stream");
  }

  /** A tenant nobody has ever touched is the good case: an empty page, not an error. */
  @Test
  void untouchedOrg_isAnEmptyPageWithAnAnchor() {
    Fixture f = twoTenants();

    JsonNode body = timeline(f.acme);

    assertEquals(0, body.path("data").size());
    assertEquals(0, body.path("total").asLong());
    assertFalse(body.path("created_at").isMissingNode());
  }

  // ------------------------------------------------------------------ contract

  /**
   * <strong>404, not an empty page.</strong> This is a lookup on a path segment — the segment names
   * a thing, and a thing that does not exist is absent. Slice 2's {@code ?org_id=} on the queues
   * yields an empty page for the opposite and equally deliberate reason: a query parameter narrows
   * a set. Neither should be "fixed" to match the other.
   */
  @Test
  void unknownOrg_is404() {
    twoTenants();
    assertEquals(
        404,
        invoke("GET", platform(SystemRole.ADMIN), "/" + UUID.randomUUID() + "/timeline", "")
            .status);
  }

  @Test
  void post_is405() {
    Fixture f = twoTenants();
    assertEquals(
        405, invoke("POST", platform(SystemRole.ADMIN), "/" + f.acme + "/timeline", "").status);
    assertEquals(
        405, invoke("DELETE", platform(SystemRole.ADMIN), "/" + f.acme + "/timeline", "").status);
    assertEquals(
        405, invoke("PATCH", platform(SystemRole.ADMIN), "/" + f.acme + "/timeline", "").status);
  }

  /** An unknown subresource is not the timeline and not the detail. */
  @Test
  void unknownSubresource_is404() {
    Fixture f = twoTenants();
    assertEquals(
        404, invoke("GET", platform(SystemRole.ADMIN), "/" + f.acme + "/history", "").status);
  }

  /** Reading a trail is a read, so SUPPORT sees it — byte-identical to what ADMIN sees. */
  @Test
  void support_reads200() {
    Fixture f = twoTenants();
    insertAudit(f.admin, f.acme, "ORG_UPDATE", "ORG", f.acme, null, minutes(-1));

    JsonNode admin = read(platform(SystemRole.ADMIN), "/" + f.acme + "/timeline", "", 200);
    JsonNode support = read(platform(SystemRole.SUPPORT), "/" + f.acme + "/timeline", "", 200);

    assertEquals(admin, support, "nothing on this read is tier-gated");
  }

  /**
   * An OWNER cannot read their own tenant's platform timeline. Deliberate — it names operators and
   * impersonations — and worth a test so removing it later is a visible decision.
   */
  @Test
  void orgOwner_is403() {
    Fixture f = twoTenants();
    assertEquals(403, invoke("GET", orgOwnerOf(f.acme), "/" + f.acme + "/timeline", "").status);
  }

  @Test
  void anonymous_is401() {
    Fixture f = twoTenants();
    assertEquals(401, invoke("GET", null, "/" + f.acme + "/timeline", "").status);
  }

  /** Authorization precedes the org lookup, so a 404 is never an existence oracle. */
  @Test
  void authzPrecedesLookup() {
    assertEquals(401, invoke("GET", null, "/" + UUID.randomUUID() + "/timeline", "").status);
    assertEquals(
        403,
        invoke("GET", orgOwnerOf(UUID.randomUUID()), "/" + UUID.randomUUID() + "/timeline", "")
            .status);
  }

  // ------------------------------------------------------------------ fixtures

  /** Two tenants and the cast every test shares. */
  private record Fixture(UUID acme, UUID beta, UUID admin, UUID member) {}

  private Fixture twoTenants() {
    UUID acme = createOrg("acme", "Acme Trading");
    UUID beta = createOrg("beta", "Beta Stores");
    UUID admin = createUser("admin@platform.test", "Ops Admin");
    UUID member = createUser("member@acme.test", "Hana Youssef");
    return new Fixture(acme, beta, admin, member);
  }

  private UUID createOrg(String slug, String name) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG).set(ORG.ID, id).set(ORG.NAME, name).set(ORG.SLUG, slug).execute();
    return id;
  }

  private UUID createUser(String email, String displayName) {
    AppUserRecord existing = dsl.fetchOne(APP_USER, APP_USER.EMAIL.eq(email));
    if (existing != null) {
      return existing.getId();
    }
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, email)
        .set(APP_USER.PASSWORD_HASH, "$2a$10$notarealhashjustfixturepadding000000000000000000000")
        .set(APP_USER.ACTOR_TYPE, com.loai.inventory.repository.generated.enums.ActorType.USER)
        .set(APP_USER.DISPLAY_NAME, displayName)
        .execute();
    return id;
  }

  /** An audit row in today's shape — {@code org_id} set. */
  private void insertAudit(
      UUID actor,
      UUID orgId,
      String action,
      String targetType,
      UUID targetId,
      String detailJson,
      OffsetDateTime at) {
    dsl.insertInto(PLATFORM_AUDIT)
        .set(PLATFORM_AUDIT.ID, UUID.randomUUID())
        .set(PLATFORM_AUDIT.ACTOR_ID, actor)
        .set(PLATFORM_AUDIT.ORG_ID, orgId)
        .set(PLATFORM_AUDIT.ACTION, action)
        .set(PLATFORM_AUDIT.TARGET_TYPE, targetType)
        .set(PLATFORM_AUDIT.TARGET_ID, targetId)
        .set(PLATFORM_AUDIT.DETAIL, detailJson == null ? null : JSONB.valueOf(detailJson))
        .set(PLATFORM_AUDIT.CREATED_AT, at)
        .execute();
  }

  /** An audit row in the <em>pre-V76</em> shape — {@code org_id} deliberately NULL. */
  private void insertLegacyAudit(
      UUID actor,
      String action,
      String targetType,
      UUID targetId,
      String detailJson,
      OffsetDateTime at) {
    insertAudit(actor, null, action, targetType, targetId, detailJson, at);
  }

  private void insertImpersonation(
      UUID impersonator, UUID target, String tier, UUID scopeOrg, String event, OffsetDateTime at) {
    dsl.insertInto(IMPERSONATION_EVENT)
        .set(IMPERSONATION_EVENT.ID, UUID.randomUUID())
        .set(IMPERSONATION_EVENT.IMPERSONATOR_ID, impersonator)
        .set(IMPERSONATION_EVENT.TARGET_ID, target)
        .set(IMPERSONATION_EVENT.TIER, tier)
        .set(IMPERSONATION_EVENT.SCOPE_ORG_ID, scopeOrg)
        .set(IMPERSONATION_EVENT.EVENT, event)
        .set(IMPERSONATION_EVENT.CREATED_AT, at)
        .execute();
  }

  /** V76's third backfill statement, verbatim, so the test exercises the migration's own SQL. */
  private void runV76ProvisionedOwnerBackfill() {
    dsl.execute(
        """
        UPDATE platform_audit u
           SET org_id = o.target_id
          FROM platform_audit o
         WHERE u.org_id IS NULL
           AND u.action = 'USER_CREATE'
           AND u.detail->>'via' = 'org_provision'
           AND o.action = 'ORG_CREATE'
           AND o.detail->>'owner_minted' = 'true'
           AND (o.detail->>'owner_id')::uuid = u.target_id
           AND EXISTS (SELECT 1 FROM org g WHERE g.id = o.target_id)
        """);
  }

  private UUID orgIdOfUserCreateFor(UUID ownerId) {
    return dsl.select(PLATFORM_AUDIT.ORG_ID)
        .from(PLATFORM_AUDIT)
        .where(PLATFORM_AUDIT.ACTION.eq("USER_CREATE"))
        .and(PLATFORM_AUDIT.TARGET_ID.eq(ownerId))
        .fetchOne(PLATFORM_AUDIT.ORG_ID);
  }

  private static String ownerMinted(UUID ownerId, boolean minted) {
    return "{\"slug\":\"x\",\"owner_id\":\"" + ownerId + "\",\"owner_minted\":" + minted + "}";
  }

  private static String provisionDetail() {
    return "{\"email\":\"o@x.test\",\"via\":\"org_provision\"}";
  }

  private static String reason(String reason) {
    return "{\"reason\":" + quote(reason) + "}";
  }

  private static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static OffsetDateTime minutes(int delta) {
    return OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(delta);
  }

  private static OffsetDateTime days(int delta) {
    return OffsetDateTime.now(ZoneOffset.UTC).plusDays(delta);
  }

  // ------------------------------------------------------------------ plumbing

  private OrgAdminHandler handler() {
    return new OrgAdminHandler(orgService, timelineService, mapper);
  }

  private JsonNode timeline(UUID orgId) {
    return read(platform(SystemRole.ADMIN), "/" + orgId + "/timeline", "", 200);
  }

  /** Every action on a tenant's timeline, newest-first. */
  private List<String> actionsOn(UUID orgId) {
    return actionsIn(timeline(orgId).path("data"));
  }

  private List<String> actionsIn(JsonNode data) {
    List<String> actions = new ArrayList<>();
    data.forEach(e -> actions.add(e.path("action").asText()));
    return actions;
  }

  private List<String> sourcesIn(JsonNode data) {
    List<String> sources = new ArrayList<>();
    data.forEach(e -> sources.add(e.path("source").asText()));
    return sources;
  }

  private JsonNode entryWithAction(JsonNode body, String action) {
    for (JsonNode entry : body.path("data")) {
      if (action.equals(entry.path("action").asText())) {
        return entry;
      }
    }
    throw new AssertionError("no '" + action + "' entry in " + body);
  }

  private boolean newestFirst(JsonNode data) {
    List<JsonNode> nodes = new ArrayList<>();
    data.forEach(nodes::add);
    return newestFirstNodes(nodes);
  }

  private boolean newestFirstNodes(List<JsonNode> nodes) {
    for (int i = 1; i < nodes.size(); i++) {
      if (nodes.get(i - 1).path("at").asText().compareTo(nodes.get(i).path("at").asText()) < 0) {
        return false;
      }
    }
    return true;
  }

  private JsonNode read(SecurityContext ctx, String remaining, String query, int expectedStatus) {
    Resp resp = invoke("GET", ctx, remaining, query);
    assertEquals(expectedStatus, resp.status, () -> "body was " + bodyOf(resp).toString());
    return bodyOf(resp);
  }

  private Resp invoke(String method, SecurityContext ctx, String remaining, String query) {
    try {
      Resp resp = new Resp();
      handler().handle(method, reqWith(ctx, query), resp.mock, remaining);
      return resp;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode bodyOf(Resp resp) {
    try {
      return mapper.readTree(resp.body.toByteArray());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Environment env() {
    return new Environment(Instant.now(), "1.2.3.4", "junit");
  }

  private SecurityContext actorCtx(UUID actorId) {
    return new SecurityContext(
        actorId, ActorType.USER, Set.of(SystemRole.ADMIN), Map.of(), Set.of(), 0);
  }

  private SecurityContext platform(SystemRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(role), Map.of(), Set.of(), 0);
  }

  private SecurityContext orgOwnerOf(UUID orgId) {
    return new SecurityContext(
        UUID.randomUUID(),
        ActorType.USER,
        Set.of(),
        Map.of(orgId, Set.of(OrgRole.OWNER)),
        Set.of(),
        0);
  }

  private HttpServletRequest reqWith(SecurityContext ctx, String query) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    if (query != null && query.startsWith("?")) {
      for (String pair : query.substring(1).split("&")) {
        int eq = pair.indexOf('=');
        if (eq > 0) {
          when(req.getParameter(pair.substring(0, eq))).thenReturn(pair.substring(eq + 1));
        }
      }
    }
    return req;
  }

  private static final class Resp {
    final HttpServletResponse mock;
    final ByteArrayOutputStream body = new ByteArrayOutputStream();
    int status = 200;

    Resp() throws IOException {
      mock = Mockito.mock(HttpServletResponse.class);
      Mockito.doAnswer(
              inv -> {
                status = inv.getArgument(0);
                return null;
              })
          .when(mock)
          .setStatus(Mockito.anyInt());
      when(mock.getOutputStream())
          .thenReturn(
              new ServletOutputStream() {
                @Override
                public void write(int b) {
                  body.write(b);
                }

                @Override
                public boolean isReady() {
                  return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {}
              });
    }
  }
}
