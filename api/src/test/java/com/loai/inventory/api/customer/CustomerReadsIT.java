package com.loai.inventory.api.customer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.servlet.handler.CustomerHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.service.CustomerService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code stories/org_customer_reads.md} — the customer read stops being a pre-commerce stub.
 *
 * <p><b>Two orgs throughout.</b> Every read here is org-scoped by construction, and the only way to
 * prove that is a second tenant holding an identically-named, identically-emailed customer that
 * must never appear.
 */
@Testcontainers
class CustomerReadsIT {

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
  static CustomerHandler handler;

  static UUID orgA;
  static UUID orgB;

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
    handler =
        new CustomerHandler(
            new CustomerService(
                dsl, new CustomerRepositoryFactoryImpl(), new SalesOrderRepositoryFactoryImpl()),
            mapper);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute("TRUNCATE sales_order, customer, org RESTART IDENTITY CASCADE");
    orgA = org("acme");
    orgB = org("other");
  }

  // ─── The record ───────────────────────────────────────────────────────────────────────────

  /**
   * The stub's whole problem in one assertion. A checkout-created customer has a name, a phone and
   * an address that the model has always held and the DTO always dropped; a {@code POST /customers}
   * customer has only an email, and its absent fields must be <em>absent</em> rather than empty
   * strings — a directory that renders "" for a missing name looks broken, not empty.
   */
  @Test
  void listCarriesTheFullRecord() {
    customer(orgA, "shopper@acme.test", "نادية علي", "01001234567", "12 Nile St, Cairo");
    customer(orgA, "bare@acme.test", null, null, null);

    JsonNode rows = read(viewer(orgA), "", "").get("data");
    JsonNode full = rowByEmail(rows, "shopper@acme.test");
    assertEquals("نادية علي", full.get("name").asText());
    assertEquals("01001234567", full.get("phone").asText());
    assertEquals("12 Nile St, Cairo", full.get("address").asText());

    JsonNode bare = rowByEmail(rows, "bare@acme.test");
    assertFalse(bare.has("name"), "an absent name must be an absent key, not an empty string");
    assertFalse(bare.has("phone"));
    assertFalse(bare.has("address"));
  }

  // ─── Search ───────────────────────────────────────────────────────────────────────────────

  @Test
  void search_matchesNameAndEmail() {
    customer(orgA, "nadia@acme.test", "Nadia Ali", null, null);
    customer(orgA, "someone@acme.test", "Omar Farouk", null, null);

    assertEquals(1, total(read(viewer(orgA), "", "?q=Nadia")), "matched by name");
    assertEquals(1, total(read(viewer(orgA), "", "?q=someone")), "matched by email");
    // OR'd, so a term hitting both legs still returns each customer once.
    assertEquals(2, total(read(viewer(orgA), "", "?q=a")));
  }

  /**
   * The only assertion that proves {@code fold_search} is applied on <em>both</em> sides. A fold on
   * the stored column alone still matches the exact spelling, so a one-sided bug passes every test
   * that searches for the string it inserted.
   */
  @Test
  void search_isDiacriticAndCaseInsensitive() {
    customer(orgA, "ahmed@acme.test", "أحمد محمّد", null, null);

    assertEquals(1, total(read(viewer(orgA), "", "?q=احمد")), "unhamzated alif must still match");
    assertEquals(1, total(read(viewer(orgA), "", "?q=محمد")), "a dropped shadda must still match");
    customer(orgA, "casey@acme.test", "Casey Jones", null, null);
    assertEquals(1, total(read(viewer(orgA), "", "?q=casey")), "email/name case must not matter");
    assertEquals(1, total(read(viewer(orgA), "", "?q=CASEY")));
  }

  /**
   * <b>No minimum length, and that is a measurement rather than a copied constant.</b> Slice 3's
   * 2-char floor existed because a cross-org trigram probe cost 1706 ms; this query is org-scoped,
   * the planner never chooses the GIN index for it, and a one-character {@code q} measured
   * 0.24–0.65 ms on {@code perfdb}. So a single character is served, not refused — the story
   * expected a 400 here and the number did not support one.
   */
  @Test
  void search_singleCharacterIsServedNotRefused() {
    customer(orgA, "nadia@acme.test", "Nadia", null, null);
    JsonNode body = read(viewer(orgA), "", "?q=n");
    assertEquals(1, total(body));
  }

  /** Whitespace-only is absent, not a search for spaces. */
  @Test
  void search_blankIsTheUnfilteredList() {
    customer(orgA, "a@acme.test", "A", null, null);
    customer(orgA, "b@acme.test", "B", null, null);
    assertEquals(2, total(read(viewer(orgA), "", "?q=")));
    assertEquals(2, total(read(viewer(orgA), "", "?q=%20%20")));
  }

  /**
   * An explicit non-goal, written down so nobody adds it silently: {@code q} matches name and
   * email, <b>not phone</b>. Phone search is a decision with its own normalization ({@code
   * Text.normalizeNumeric}, which folds Arabic-Indic digits) and its own index question.
   */
  @Test
  void search_doesNotMatchPhone() {
    customer(orgA, "shopper@acme.test", "Nadia", "01009998888", null);
    assertEquals(0, total(read(viewer(orgA), "", "?q=01009998888")));
  }

  @Test
  void search_neverCrossesOrgs() {
    customer(orgA, "twin@acme.test", "Twin Name", null, null);
    customer(orgB, "twin@other.test", "Twin Name", null, null);

    JsonNode body = read(viewer(orgA), "", "?q=Twin");
    assertEquals(1, total(body));
    assertEquals("twin@acme.test", body.get("data").get(0).get("email").asText());
  }

  /**
   * The deviation from every other paged read here, pinned. A directory that re-sorts itself while
   * the operator types is disorienting for no gain, so {@code created_at DESC} holds with and
   * without {@code q} — the queue-vs-ledger convention deliberately does not apply.
   */
  @Test
  void listOrderingIsStableUnderSearch() {
    UUID oldest = customer(orgA, "old@acme.test", "Search Me Old", null, null);
    UUID middle = customer(orgA, "mid@acme.test", "Search Me Mid", null, null);
    UUID newest = customer(orgA, "new@acme.test", "Search Me New", null, null);
    stampCreated(oldest, "2026-01-01T00:00:00Z");
    stampCreated(middle, "2026-02-01T00:00:00Z");
    stampCreated(newest, "2026-03-01T00:00:00Z");

    assertEquals(List.of(newest, middle, oldest), ids(read(viewer(orgA), "", "")));
    assertEquals(
        List.of(newest, middle, oldest),
        ids(read(viewer(orgA), "", "?q=Search")),
        "the filtered list must not flip to oldest-first");
  }

  // ─── The orders subresource ───────────────────────────────────────────────────────────────

  @Test
  void customerOrders_newestFirst() {
    UUID c = customer(orgA, "shopper@acme.test", "Nadia", null, null);
    order(orgA, c, "SO-2026-00001", "2026-01-01T00:00:00Z");
    order(orgA, c, "SO-2026-00002", "2026-02-01T00:00:00Z");
    order(orgA, c, "SO-2026-00003", "2026-03-01T00:00:00Z");

    JsonNode body = read(viewer(orgA), "/" + c + "/orders", "");
    assertEquals(3, total(body));
    assertEquals(
        List.of("SO-2026-00003", "SO-2026-00002", "SO-2026-00001"),
        body.get("data").findValuesAsText("order_number"),
        "a customer's history is read as a ledger — the last thing they bought is the question");
    // The lean row carries the money meter and the state, and nothing heavier.
    JsonNode first = body.get("data").get(0);
    assertNotNull(first.get("status"));
    assertNotNull(first.get("grand_total"));
    assertFalse(first.has("lines"), "the list row stays lean; lines live on the order detail");
  }

  /** A path segment names a thing: an unknown customer is absent, not an empty history. */
  @Test
  void customerOrders_unknownCustomerIs404() {
    assertEquals(404, status(viewer(orgA), "GET", "/" + UUID.randomUUID() + "/orders", ""));
  }

  /** …but a real customer who has never bought anything is an ordinary empty page. */
  @Test
  void customerOrders_noOrdersIsEmptyPage() {
    UUID c = customer(orgA, "quiet@acme.test", "Quiet", null, null);
    JsonNode body = read(viewer(orgA), "/" + c + "/orders", "");
    assertEquals(0, total(body));
    assertEquals(0, body.get("data").size());
  }

  @Test
  void customerOrders_neverCrossesOrgs() {
    UUID mine = customer(orgA, "shopper@acme.test", "Nadia", null, null);
    UUID theirs = customer(orgB, "shopper@other.test", "Nadia", null, null);
    order(orgA, mine, "SO-2026-00001", "2026-01-01T00:00:00Z");
    order(orgB, theirs, "SO-2026-00009", "2026-01-01T00:00:00Z");

    // Org A's viewer asking for org B's customer gets a 404 — the customer isn't in their org.
    assertEquals(404, status(viewer(orgA), "GET", "/" + theirs + "/orders", ""));
    assertEquals(1, total(read(viewer(orgA), "/" + mine + "/orders", "")));
  }

  @Test
  void customerOrders_rejectsNonGet() {
    UUID c = customer(orgA, "shopper@acme.test", "Nadia", null, null);
    assertEquals(405, status(viewer(orgA), "POST", "/" + c + "/orders", ""));
  }

  @Test
  void unknownSubresourceIs404() {
    UUID c = customer(orgA, "shopper@acme.test", "Nadia", null, null);
    assertEquals(404, status(viewer(orgA), "GET", "/" + c + "/invoices", ""));
  }

  /**
   * <b>Enriching the org-plane DTO is not a precedent for the cross-org one.</b> Re-asserted from
   * this branch, and structurally rather than over a fixture: a shared mapper is exactly how the
   * address that {@link com.loai.inventory.api.dto.CustomerResponse} now carries would leak
   * sideways into a read that platform operators make across every tenant. {@code
   * PlatformSearchIT.results_carryNoCustomerPii} pins the behaviour; this pins the shape, so a
   * component added to the record fails here the moment it is added.
   */
  @Test
  void platformSearchStillCarriesNoCustomerPii() {
    Set<String> components =
        java.util.Arrays.stream(
                com.loai.inventory.domain.model.PlatformSearchResult.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(java.util.stream.Collectors.toSet());
    assertEquals(
        Set.of("type", "id", "label", "sublabel", "org"),
        components,
        "PlatformSearchResult gained a field — if it carries customer name/phone/address, that is"
            + " the org-plane enrichment leaking across tenants");
  }

  // ─── Tiers ────────────────────────────────────────────────────────────────────────────────

  @Test
  void viewerCanRead() {
    customer(orgA, "shopper@acme.test", "Nadia", null, null);
    assertEquals(200, status(viewer(orgA), "GET", "", ""));
  }

  @Test
  void anonymous_is401() {
    assertEquals(401, status(null, "GET", "", ""));
  }

  @Test
  void otherOrgMember_is403() {
    assertEquals(403, status(viewer(orgB), "GET", "", ""));
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────────────────────

  private static UUID org(String slug) {
    UUID id = UUID.randomUUID();
    dsl.execute("INSERT INTO org(id,name,slug,active) VALUES (?,?,?,true)", id, slug, slug);
    return id;
  }

  private static UUID customer(UUID orgId, String email, String name, String phone, String addr) {
    UUID id = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO customer(id,org_id,email,name,phone,address) VALUES (?,?,?,?,?,?)",
        id,
        orgId,
        email,
        name,
        phone,
        addr);
    return id;
  }

  private static void stampCreated(UUID id, String iso) {
    dsl.execute("UPDATE customer SET created_at = ?::timestamptz WHERE id = ?", iso, id);
  }

  private static void order(UUID orgId, UUID customerId, String number, String placedAt) {
    dsl.execute(
        "INSERT INTO sales_order(id,org_id,customer_id,order_number,channel,status,grand_total,"
            + "placed_at) VALUES (?,?,?,?,'ONLINE'::order_channel,'PAID'::order_status,100.00,"
            + "?::timestamptz)",
        UUID.randomUUID(),
        orgId,
        customerId,
        number,
        placedAt);
  }

  private static JsonNode rowByEmail(JsonNode rows, String email) {
    for (JsonNode r : rows) {
      if (email.equals(r.path("email").asText())) return r;
    }
    throw new AssertionError("no row for " + email);
  }

  private static List<UUID> ids(JsonNode body) {
    return body.get("data").findValuesAsText("id").stream().map(UUID::fromString).toList();
  }

  private static long total(JsonNode body) {
    return body.get("total").asLong();
  }

  private JsonNode read(SecurityContext ctx, String remaining, String query) {
    Resp resp = invoke(ctx, "GET", remaining, query);
    assertEquals(200, resp.status, () -> "body was " + new String(resp.body.toByteArray()));
    try {
      return mapper.readTree(resp.body.toByteArray());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private int status(SecurityContext ctx, String method, String remaining, String query) {
    return invoke(ctx, method, remaining, query).status;
  }

  private Resp invoke(SecurityContext ctx, String method, String remaining, String query) {
    try {
      Resp resp = new Resp();
      handler.handle(method, reqWith(ctx, query), resp.mock, orgA, remaining);
      return resp;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static SecurityContext viewer(UUID orgId) {
    return new SecurityContext(
        UUID.randomUUID(),
        ActorType.USER,
        Set.of(),
        Map.of(orgId, Set.of(OrgRole.VIEWER)),
        Set.of(),
        0);
  }

  private static HttpServletRequest reqWith(SecurityContext ctx, String query) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    if (query != null && query.startsWith("?")) {
      for (String pair : query.substring(1).split("&")) {
        int eq = pair.indexOf('=');
        if (eq >= 0) {
          String value =
              java.net.URLDecoder.decode(
                  pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
          when(req.getParameter(pair.substring(0, eq))).thenReturn(value);
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
