package com.loai.inventory.api.admin;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.ORG_MILESTONE;
import static com.loai.inventory.repository.generated.Tables.PLATFORM_AUDIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.servlet.handler.FunnelAdminHandler;
import com.loai.inventory.api.servlet.handler.GrowthAdminHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.repository.PlatformFunnelRepositoryFactoryImpl;
import com.loai.inventory.service.platform.PlatformFunnelService;
import com.loai.inventory.service.platform.PlatformGrowthService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import org.mockito.Mockito;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration coverage for {@code GET /api/admin/growth} (slice 8, {@code
 * stories/platform_growth_series.md}).
 *
 * <p><strong>Events are hand-inserted, deliberately</strong> — the write sites are slice 7's tested
 * property ({@link PlatformFunnelIT}); this slice's property is the <em>read</em>: how events
 * bucket, how the window bounds {@code reached_at}, and how the event surface refuses to be the
 * cohort surface. Which is also why the wiring here is two handlers over a bare schema rather than
 * {@link PlatformFunnelIT}'s full service graph: the funnel handler is only present because the one
 * deliberate bridge between the two surfaces (Σ REGISTERED points == cohort size) is pinned by
 * calling both.
 */
@Testcontainers
class PlatformGrowthIT {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ObjectMapper mapper;
  static GrowthAdminHandler handler;
  static FunnelAdminHandler funnelHandler;

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
    mapper = ObjectMapperProvider.build();

    PlatformFunnelRepositoryFactoryImpl factory = new PlatformFunnelRepositoryFactoryImpl();
    handler = new GrowthAdminHandler(new PlatformGrowthService(dsl, factory), mapper);
    funnelHandler = new FunnelAdminHandler(new PlatformFunnelService(dsl, factory), mapper);
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
        "TRUNCATE org_milestone, platform_audit, user_org_role, app_user, org"
            + " RESTART IDENTITY CASCADE");
  }

  // ------------------------------------------------------ the distinction that IS the slice

  /**
   * An org registered far outside the window whose FIRST_ORDER lands inside it appears in the
   * <em>series</em> and <strong>not</strong> in the same-window funnel <em>cohort</em> — asserted
   * in both directions so nobody "fixes" the growth series to agree with the funnel.
   */
  @Test
  void seriesIsEventBased_notCohortBased() {
    UUID org = createSelfServeOrg("old-timer", daysAgo(400));
    stamp(org, "FIRST_ORDER", daysAgo(10));

    JsonNode growth = growth("?window=90d&bucket=week&path=self_serve");
    assertEquals(1, sumOf(seriesOf(growth, "FIRST_ORDER")), "the event is inside the window");
    assertEquals(0, sumOf(seriesOf(growth, "REGISTERED")), "its registration is not");

    JsonNode funnel = funnel("?cohort=90d&path=self_serve");
    assertEquals(
        0,
        funnel.path("cohort").path("size").asLong(),
        "the same window's funnel cohort excludes the org — cohort bounds created_at");
  }

  /**
   * The one deliberate bridge between the two surfaces, true by construction because {@code
   * REGISTERED.reached_at} <em>is</em> {@code org.created_at}: Σ REGISTERED points == the funnel's
   * {@code cohort.size} for the same window+path. Nothing else may be reconciled.
   */
  @Test
  void registeredSeriesSum_equalsFunnelCohortSize() {
    createSelfServeOrg("in-window-a", daysAgo(10));
    createSelfServeOrg("in-window-b", daysAgo(40));
    createSelfServeOrg("out-of-window", daysAgo(200));
    createProvisionedOrg("provisioned-in-window", daysAgo(5));

    JsonNode growth = growth("?window=90d&bucket=week&path=self_serve");
    JsonNode funnel = funnel("?cohort=90d&path=self_serve");

    assertEquals(2, funnel.path("cohort").path("size").asLong(), "fixture control");
    assertEquals(
        funnel.path("cohort").path("size").asLong(), sumOf(seriesOf(growth, "REGISTERED")));
  }

  // ------------------------------------------------------ sparsity and the six-stage envelope

  /** No zero-count points; all six stages always present, an untouched one as {@code []}. */
  @Test
  void sparseBucketsAreOmitted_andEmptyStageIsAnEmptyArray() {
    createSelfServeOrg("week-one", utc(2026, 6, 2));
    createSelfServeOrg("week-three", utc(2026, 6, 16));

    JsonNode growth = growth("?window=all&bucket=week&path=all");

    JsonNode registered = seriesOf(growth, "REGISTERED");
    assertEquals(2, registered.size(), "the empty interior week must yield no row at all");
    for (JsonNode point : registered) {
      assertTrue(point.path("count").asLong() > 0, "no zero-count point is ever fabricated");
    }
    assertEquals(6, growth.path("series").size(), "all six stages, always");
    assertEquals(
        0,
        seriesOf(growth, "FIRST_PAYMENT").size(),
        "a stage nobody reached is points: [], never omitted");
  }

  /** UTC bucket edges: one instant, one bucket — pinned on the Postgres side of the contract. */
  @Test
  void bucketBoundariesAreUtc() {
    // 2026-06-08 is a Monday; 00:00:00 UTC is the first instant of its week.
    createSelfServeOrg("at-the-edge", OffsetDateTime.of(2026, 6, 8, 0, 0, 0, 0, ZoneOffset.UTC));
    createSelfServeOrg("just-before", OffsetDateTime.of(2026, 6, 7, 23, 59, 59, 0, ZoneOffset.UTC));

    JsonNode registered = seriesOf(growth("?window=all&bucket=week&path=all"), "REGISTERED");

    assertEquals(2, registered.size(), "one second apart across the edge is two buckets");
    assertEquals("2026-06-01T00:00:00Z", registered.get(0).path("period").asText());
    assertEquals("2026-06-08T00:00:00Z", registered.get(1).path("period").asText());
  }

  // ------------------------------------------------------ the open bucket

  /**
   * {@code current_period} names the still-open bucket on the envelope, and an event stamped "now"
   * appears under it rather than vanishing. This is also the pin holding the Java {@code
   * date_trunc} ({@link PlatformGrowthService#currentPeriod}) against the Postgres one the
   * repository groups by — if the two week/month semantics ever drift, this test fails.
   */
  @Test
  void currentPeriodNamesTheOpenBucket_andItsEventsAreNotDropped() {
    UUID org = createSelfServeOrg("fresh", daysAgo(30));
    stamp(org, "FIRST_ORDER", OffsetDateTime.now(ZoneOffset.UTC));

    for (String bucket : new String[] {"week", "month"}) {
      JsonNode growth = growth("?window=90d&bucket=" + bucket + "&path=all");
      String currentPeriod = growth.path("current_period").asText();
      assertTrue(!currentPeriod.isEmpty(), "current_period must be on the envelope");

      JsonNode firstOrder = seriesOf(growth, "FIRST_ORDER");
      assertEquals(1, firstOrder.size());
      assertEquals(
          currentPeriod,
          firstOrder.get(0).path("period").asText(),
          "an event stamped now must land under current_period (bucket=" + bucket + ")");
      assertEquals(1, firstOrder.get(0).path("count").asLong());
    }
  }

  // ------------------------------------------------------ path and window semantics

  /** Provisioned events never appear under {@code path=self_serve}; {@code all} is the sum. */
  @Test
  void pathFilterSeparatesTheSeries() {
    UUID selfServe = createSelfServeOrg("indie", daysAgo(50));
    UUID provisioned = createProvisionedOrg("enterprise", daysAgo(50));
    stamp(selfServe, "FIRST_ORDER", utc(2026, 7, 8));
    stamp(provisioned, "FIRST_ORDER", utc(2026, 7, 9)); // same UTC week, different day

    JsonNode self = seriesOf(growth("?window=90d&bucket=week&path=self_serve"), "FIRST_ORDER");
    JsonNode prov = seriesOf(growth("?window=90d&bucket=week&path=provisioned"), "FIRST_ORDER");
    JsonNode all = seriesOf(growth("?window=90d&bucket=week&path=all"), "FIRST_ORDER");

    assertEquals(1, sumOf(self));
    assertEquals(1, sumOf(prov));
    assertEquals(1, all.size(), "same week — one bucket");
    assertEquals(2, all.get(0).path("count").asLong(), "all is the per-bucket sum of the paths");
    assertEquals(self.get(0).path("period").asText(), all.get(0).path("period").asText());
  }

  /** The same org contributes different stages to different windows — the window bounds events. */
  @Test
  void windowBoundsReachedAt() {
    UUID org = createSelfServeOrg("grower", daysAgo(200));
    stamp(org, "FIRST_ORDER", daysAgo(10));

    JsonNode narrow = growth("?window=90d&bucket=week&path=all");
    assertEquals(0, sumOf(seriesOf(narrow, "REGISTERED")));
    assertEquals(1, sumOf(seriesOf(narrow, "FIRST_ORDER")));

    JsonNode wide = growth("?window=365d&bucket=week&path=all");
    assertEquals(1, sumOf(seriesOf(wide, "REGISTERED")));
    assertEquals(1, sumOf(seriesOf(wide, "FIRST_ORDER")));
  }

  // ------------------------------------------------------ the open-text guarantee, again

  /** A milestone the enum does not know is stored and simply not rendered — no seventh series. */
  @Test
  void unknownMilestoneRowIsNotRendered() {
    UUID org = createSelfServeOrg("acme", daysAgo(10));
    stamp(org, "FIRST_EXPORT_V9", daysAgo(5));

    JsonNode growth = growth("?window=90d&bucket=week&path=all");

    assertEquals(6, growth.path("series").size());
    for (JsonNode series : growth.path("series")) {
      assertTrue(!"FIRST_EXPORT_V9".equals(series.path("stage").asText()));
    }
  }

  // ------------------------------------------------------ envelope

  /** {@code from} is absent exactly for {@code window=all}; {@code as_of} is always there. */
  @Test
  void fromIsOnTheEnvelope_exceptForWindowAll() {
    JsonNode bounded = growth("?window=90d&bucket=week&path=all");
    assertTrue(!bounded.path("from").isMissingNode());
    assertTrue(!bounded.path("as_of").isMissingNode());

    JsonNode unbounded = growth("?window=all&bucket=week&path=all");
    assertTrue(unbounded.path("from").isMissingNode(), "an unbounded window has no start to name");
    assertTrue(!unbounded.path("current_period").isMissingNode());
  }

  // ------------------------------------------------------ contract

  @Test
  void unknownWindow_is400() {
    Resp resp = invoke("GET", platform(SystemRole.ADMIN), "", "?window=30d&bucket=week&path=all");
    assertEquals(400, resp.status);
    assertTrue(bodyOf(resp).path("message").asText().contains("90d"), "the 400 must name options");
  }

  @Test
  void unknownBucket_is400() {
    Resp resp = invoke("GET", platform(SystemRole.ADMIN), "", "?window=90d&bucket=day&path=all");
    assertEquals(400, resp.status);
    assertTrue(bodyOf(resp).path("message").asText().contains("month"));
  }

  @Test
  void unknownPath_is400() {
    Resp resp =
        invoke("GET", platform(SystemRole.ADMIN), "", "?window=90d&bucket=week&path=organic");
    assertEquals(400, resp.status);
    assertTrue(bodyOf(resp).path("message").asText().contains("self_serve"));
  }

  @Test
  void post_is405() {
    assertEquals(
        405,
        invoke("POST", platform(SystemRole.ADMIN), "", "?window=90d&bucket=week&path=all").status);
  }

  @Test
  void subpath_is404() {
    assertEquals(
        404,
        invoke("GET", platform(SystemRole.ADMIN), "/series", "?window=90d&bucket=week&path=all")
            .status);
  }

  @Test
  void support_reads200() {
    createSelfServeOrg("acme", daysAgo(10));
    JsonNode admin = read(platform(SystemRole.ADMIN), "?window=90d&bucket=week&path=all");
    JsonNode support = read(platform(SystemRole.SUPPORT), "?window=90d&bucket=week&path=all");
    // as_of differs between the two calls by construction; everything else must be identical.
    assertEquals(admin.path("series"), support.path("series"));
  }

  @Test
  void orgOwner_is403() {
    UUID org = createSelfServeOrg("acme", daysAgo(10));
    assertEquals(
        403, invoke("GET", orgOwnerOf(org), "", "?window=90d&bucket=week&path=all").status);
  }

  @Test
  void anonymous_is401() {
    assertEquals(401, invoke("GET", null, "", "?window=90d&bucket=week&path=all").status);
  }

  // ------------------------------------------------------ fixtures

  /** A self-serve org: no ORG_CREATE audit row; REGISTERED stamped at its own created_at. */
  private UUID createSelfServeOrg(String slug, OffsetDateTime createdAt) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug + "-" + id)
        .set(ORG.CREATED_AT, createdAt)
        .execute();
    stamp(id, "REGISTERED", createdAt);
    return id;
  }

  /** A provisioned org: the ORG_CREATE audit row is what the path split keys on (V76 org_id). */
  private UUID createProvisionedOrg(String slug, OffsetDateTime createdAt) {
    UUID id = createSelfServeOrg(slug, createdAt);
    UUID admin = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, admin)
        .set(APP_USER.EMAIL, admin + "-ops@platform.test")
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, com.loai.inventory.repository.generated.enums.ActorType.USER)
        .execute();
    dsl.insertInto(PLATFORM_AUDIT)
        .set(PLATFORM_AUDIT.ACTOR_ID, admin)
        .set(PLATFORM_AUDIT.ACTION, "ORG_CREATE")
        .set(PLATFORM_AUDIT.TARGET_TYPE, "ORG")
        .set(PLATFORM_AUDIT.TARGET_ID, id)
        .set(PLATFORM_AUDIT.ORG_ID, id)
        .set(PLATFORM_AUDIT.CREATED_AT, createdAt)
        .execute();
    return id;
  }

  private void stamp(UUID orgId, String milestone, OffsetDateTime reachedAt) {
    dsl.insertInto(ORG_MILESTONE)
        .set(ORG_MILESTONE.ORG_ID, orgId)
        .set(ORG_MILESTONE.MILESTONE, milestone)
        .set(ORG_MILESTONE.REACHED_AT, reachedAt)
        .onConflictDoNothing()
        .execute();
  }

  private static OffsetDateTime daysAgo(int days) {
    return OffsetDateTime.now(ZoneOffset.UTC).minusDays(days);
  }

  private static OffsetDateTime utc(int year, int month, int day) {
    return OffsetDateTime.of(year, month, day, 12, 0, 0, 0, ZoneOffset.UTC);
  }

  // ------------------------------------------------------ plumbing

  private JsonNode seriesOf(JsonNode growth, String stage) {
    for (JsonNode s : growth.path("series")) {
      if (stage.equals(s.path("stage").asText())) {
        return s.path("points");
      }
    }
    throw new AssertionError("stage " + stage + " absent from " + growth.path("series"));
  }

  private long sumOf(JsonNode points) {
    long sum = 0;
    for (JsonNode p : points) {
      sum += p.path("count").asLong();
    }
    return sum;
  }

  private JsonNode growth(String query) {
    return read(platform(SystemRole.ADMIN), query);
  }

  private JsonNode funnel(String query) {
    Resp resp = new Resp();
    try {
      funnelHandler.handle("GET", reqWith(platform(SystemRole.ADMIN), query), resp.mock, "");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    assertEquals(200, resp.status, () -> "funnel body was " + bodyOf(resp));
    return bodyOf(resp);
  }

  private JsonNode read(SecurityContext ctx, String query) {
    Resp resp = invoke("GET", ctx, "", query);
    assertEquals(200, resp.status, () -> "body was " + bodyOf(resp).toString());
    return bodyOf(resp);
  }

  private Resp invoke(String method, SecurityContext ctx, String remaining, String query) {
    try {
      Resp resp = new Resp();
      handler.handle(method, reqWith(ctx, query), resp.mock, remaining);
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
    Mockito.when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    if (query != null && query.startsWith("?")) {
      for (String pair : query.substring(1).split("&")) {
        int eq = pair.indexOf('=');
        if (eq > 0) {
          Mockito.when(req.getParameter(pair.substring(0, eq))).thenReturn(pair.substring(eq + 1));
        }
      }
    }
    return req;
  }

  private static final class Resp {
    final HttpServletResponse mock;
    final ByteArrayOutputStream body = new ByteArrayOutputStream();
    int status = 200;

    Resp() {
      mock = Mockito.mock(HttpServletResponse.class);
      Mockito.doAnswer(
              inv -> {
                status = inv.getArgument(0);
                return null;
              })
          .when(mock)
          .setStatus(Mockito.anyInt());
      try {
        Mockito.when(mock.getOutputStream())
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
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
