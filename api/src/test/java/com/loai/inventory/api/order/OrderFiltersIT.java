package com.loai.inventory.api.order;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.api.mapper.SalesOrderMapper;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.OrderListFilter;
import com.loai.inventory.domain.model.OrderListFilter.Balance;
import com.loai.inventory.domain.model.OrderListFilter.Sort;
import com.loai.inventory.domain.model.OrderListStats;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.repository.SalesOrderRepository;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
 * The order worklist's filter dimensions and explicit sorts ({@code stories/order_filters.md}) at
 * the repository seam: the {@code created_at} window is half-open, {@code balance} reads the row's
 * own money meter, the {@code grand_total} band is inclusive, an explicit {@code sort} overrides
 * the queue-vs-ledger rule with a stable tie-break, and the stats (count + money) come from the
 * same predicate as the rows.
 */
@Testcontainers
class OrderFiltersIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static SalesOrderRepository repo;

  /** The Cairo calendar day 2026-08-01 as the frontend sends it: UTC, half-open. */
  static final OffsetDateTime AUG_1 = OffsetDateTime.parse("2026-07-31T21:00:00Z");

  static final OffsetDateTime AUG_2 = OffsetDateTime.parse("2026-08-01T21:00:00Z");
  static final OffsetDateTime AUG_3 = OffsetDateTime.parse("2026-08-02T21:00:00Z");

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
    repo = new SalesOrderRepositoryFactoryImpl().create(dsl);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  /** Rows seeded without an explicit time get distinct, increasing ones — no id tie-breaks. */
  static final AtomicInteger SEQ = new AtomicInteger();

  @BeforeEach
  void freshSchema() {
    dsl.execute("TRUNCATE sales_order, org RESTART IDENTITY CASCADE");
    SEQ.set(0);
  }

  @Test
  void window_isHalfOpen_onCreatedAt_eitherSideMayBeOpen() {
    UUID org = createOrg("acme");
    UUID before = seed(org, spec("SO-1").at(AUG_1.minusSeconds(1)));
    UUID onFrom = seed(org, spec("SO-2").at(AUG_1));
    UUID inside = seed(org, spec("SO-3").at(AUG_1.plusHours(12)));
    UUID onTo = seed(org, spec("SO-4").at(AUG_2));

    assertEquals(
        List.of(inside, onFrom), ids(list(org, filter().from(AUG_1).to(AUG_2))), "[from, to)");
    assertEquals(
        List.of(onTo, inside, onFrom), ids(list(org, filter().from(AUG_1))), "open 'to' side");
    assertEquals(
        List.of(inside, onFrom, before), ids(list(org, filter().to(AUG_2))), "open 'from' side");
    assertEquals(2, repo.stats(org, filter().from(AUG_1).to(AUG_2).build()).total());
  }

  @Test
  void balance_readsTheRowsOwnMeter_andComposesWithStatus() {
    UUID org = createOrg("acme");
    UUID owingPending = seed(org, spec("SO-1").total("500").prepaid("0"));
    UUID owingClosed =
        seed(org, spec("SO-2").status(OrderStatus.CLOSED).total("234").prepaid("40"));
    UUID settled = seed(org, spec("SO-3").status(OrderStatus.PAID).total("100").prepaid("100"));
    UUID overpaid = seed(org, spec("SO-4").status(OrderStatus.PAID).total("1560").prepaid("1620"));
    UUID expiredOwing =
        seed(org, spec("SO-5").status(OrderStatus.EXPIRED).total("200").prepaid("0"));

    assertEquals(
        List.of(expiredOwing, owingClosed, owingPending),
        ids(list(org, filter().balance(Balance.OWING))),
        "owing is arithmetic — an expired hold still reads as owing, the way the row shows it");
    assertEquals(List.of(settled), ids(list(org, filter().balance(Balance.SETTLED))));
    assertEquals(List.of(overpaid), ids(list(org, filter().balance(Balance.OVERPAID))));
    assertEquals(
        List.of(owingClosed),
        ids(list(org, filter().status(OrderStatus.CLOSED).balance(Balance.OWING))),
        "composes with the tab");
  }

  @Test
  void amountBand_isInclusive_bothEnds() {
    UUID org = createOrg("acme");
    UUID low = seed(org, spec("SO-1").total("99.99"));
    UUID min = seed(org, spec("SO-2").total("100.00"));
    UUID mid = seed(org, spec("SO-3").total("250.00"));
    UUID max = seed(org, spec("SO-4").total("500.00"));
    UUID high = seed(org, spec("SO-5").total("500.01"));

    assertEquals(
        List.of(max, mid, min),
        ids(list(org, filter().min(new BigDecimal("100")).max(new BigDecimal("500")))));
    assertEquals(List.of(high, max), ids(list(org, filter().min(new BigDecimal("500")))));
    assertEquals(List.of(min, low), ids(list(org, filter().max(new BigDecimal("100")))));
  }

  @Test
  void sort_overridesQueueVsLedger_withAStableTieBreak() {
    UUID org = createOrg("acme");
    // Three PENDING holds with distinct expiries + one PAID (expires_at null).
    UUID a = seed(org, spec("SO-A").at(AUG_1).total("300").prepaid("0").expires(AUG_3));
    UUID b = seed(org, spec("SO-B").at(AUG_2).total("900").prepaid("0").expires(AUG_2));
    UUID c = seed(org, spec("SO-C").at(AUG_3).total("600").prepaid("100").expires(AUG_3));
    UUID d =
        seed(
            org,
            spec("SO-D")
                .at(AUG_3.plusHours(1))
                .status(OrderStatus.PAID)
                .total("1200")
                .prepaid("1200"));

    assertEquals(List.of(d, c, b, a), ids(list(org, filter())), "ledger: newest first");
    assertEquals(
        List.of(a, b, c),
        ids(list(org, filter().status(OrderStatus.PENDING_PAYMENT))),
        "queue: oldest first");
    assertEquals(
        List.of(c, b, a),
        ids(list(org, filter().status(OrderStatus.PENDING_PAYMENT).sort(Sort.NEWEST))),
        "an explicit sort overrides the queue rule");
    assertEquals(List.of(a, b, c, d), ids(list(org, filter().sort(Sort.OLDEST))));
    assertEquals(List.of(d, b, c, a), ids(list(org, filter().sort(Sort.TOTAL))), "biggest first");
    assertEquals(
        List.of(b, c, a, d),
        ids(list(org, filter().sort(Sort.BALANCE))),
        "most owed first: 900, 500, 300, 0");
    assertEquals(
        List.of(b, c, a, d),
        ids(list(org, filter().sort(Sort.EXPIRING))),
        "soonest expiry first; equal expiries fall back to newest; null (paid) last");
  }

  @Test
  void stats_shareThePredicate_andOnlyLiveStatusesCarryMoney() {
    UUID org = createOrg("acme");
    seed(org, spec("SO-1").total("500").prepaid("0")); // pending: owes 500
    seed(org, spec("SO-2").status(OrderStatus.CLOSED).total("234").prepaid("40")); // owes 194
    seed(org, spec("SO-3").status(OrderStatus.PAID).total("100").prepaid("100"));
    seed(org, spec("SO-4").status(OrderStatus.PAID).total("1560").prepaid("1620")); // overpaid
    seed(org, spec("SO-5").status(OrderStatus.EXPIRED).total("200").prepaid("0")); // not live
    seed(org, spec("SO-6").status(OrderStatus.CANCELLED).total("999").prepaid("0")); // not live

    OrderListStats all = repo.stats(org, filter().build());
    assertEquals(6, all.total(), "the count is the pager's total: every row, live or not");
    assertEquals(new BigDecimal("694.00"), all.outstanding(), "500 + 194; overpaid adds nothing");
    assertEquals(
        new BigDecimal("2394.00"), all.value(), "500 + 234 + 100 + 1560; expired/cancelled never");

    OrderListStats owing = repo.stats(org, filter().balance(Balance.OWING).build());
    assertEquals(4, owing.total(), "the filter narrows the count (incl. the expired/cancelled)");
    assertEquals(new BigDecimal("694.00"), owing.outstanding());
    assertEquals(new BigDecimal("734.00"), owing.value());

    OrderListStats none = repo.stats(org, filter().min(new BigDecimal("5000")).build());
    assertEquals(0, none.total());
    assertEquals(new BigDecimal("0.00"), none.outstanding(), "an empty set is 0.00, never null");
    assertEquals(new BigDecimal("0.00"), none.value());
  }

  @Test
  void filters_composeWithChannelAndQ_andAreOrgScoped() {
    UUID org = createOrg("acme");
    UUID other = createOrg("other");
    UUID hit = seed(org, spec("SO-77").channel(OrderChannel.IN_STORE).at(AUG_1).total("50"));
    seed(org, spec("SO-78").channel(OrderChannel.ONLINE).at(AUG_1).total("50"));
    seed(org, spec("SO-79").channel(OrderChannel.IN_STORE).at(AUG_3).total("50"));
    seed(other, spec("SO-77").channel(OrderChannel.IN_STORE).at(AUG_1).total("50"));

    OrderListFilter f =
        filter()
            .channel(OrderChannel.IN_STORE)
            .q("77")
            .from(AUG_1)
            .to(AUG_2)
            .max(new BigDecimal("50"))
            .build();
    assertEquals(List.of(hit), ids(repo.list(org, f, 0, 10)));
    assertEquals(1, repo.stats(org, f).total());
  }

  @Test
  void mapper_parsesEveryDimension_andNamesTheBadOne() {
    OrderListFilter f =
        SalesOrderMapper.toListFilter(
            "paid",
            " in_store ",
            "  77 ",
            "2026-07-31T21:00:00Z",
            "2026-08-31T21:00:00Z",
            "Owing",
            "100",
            "2500.50",
            "expiring");
    assertEquals(OrderStatus.PAID, f.status());
    assertEquals(OrderChannel.IN_STORE, f.channel());
    assertEquals("77", f.q());
    assertEquals(AUG_1, f.createdFrom());
    assertEquals(Balance.OWING, f.balance());
    assertEquals(Sort.EXPIRING, f.sort());
    assertEquals(0, new BigDecimal("2500.50").compareTo(f.maxTotal()));

    assertEquals(OrderListFilter.none(), blank("", "", "  ", "", "", "", "", "", ""));

    assertEquals(
        "'balance' must be one of: owing, settled, overpaid",
        bad("", "", "", "", "", "half", "", "", ""));
    assertEquals(
        "'sort' must be one of: newest, oldest, total, balance, expiring",
        bad("", "", "", "", "", "", "", "", "price"));
    assertEquals(
        "'from' must be an ISO-8601 date-time", bad("", "", "", "2026-08-01", "", "", "", "", ""));
    assertEquals(
        "'from' must be strictly before 'to'",
        bad("", "", "", "2026-09-01T00:00:00Z", "2026-08-01T00:00:00Z", "", "", "", ""));
    assertEquals("'min' must be a number", bad("", "", "", "", "", "", "abc", "", ""));
    assertEquals("'max' must not be negative", bad("", "", "", "", "", "", "", "-1", ""));
    assertEquals("'min' must not exceed 'max'", bad("", "", "", "", "", "", "900", "100", ""));
  }

  // helpers

  private static OrderListFilter blank(String... p) {
    return SalesOrderMapper.toListFilter(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8]);
  }

  private static String bad(String... p) {
    return assertThrows(ValidationException.class, () -> blank(p)).getMessage();
  }

  private static List<SalesOrder> list(UUID org, FilterBuilder b) {
    return repo.list(org, b.build(), 0, 50);
  }

  private static List<UUID> ids(List<SalesOrder> orders) {
    return orders.stream().map(SalesOrder::getId).toList();
  }

  private static FilterBuilder filter() {
    return new FilterBuilder();
  }

  private static final class FilterBuilder {
    OrderStatus status;
    OrderChannel channel;
    String q;
    OffsetDateTime from;
    OffsetDateTime to;
    Balance balance;
    BigDecimal min;
    BigDecimal max;
    Sort sort;

    FilterBuilder status(OrderStatus v) {
      status = v;
      return this;
    }

    FilterBuilder channel(OrderChannel v) {
      channel = v;
      return this;
    }

    FilterBuilder q(String v) {
      q = v;
      return this;
    }

    FilterBuilder from(OffsetDateTime v) {
      from = v;
      return this;
    }

    FilterBuilder to(OffsetDateTime v) {
      to = v;
      return this;
    }

    FilterBuilder balance(Balance v) {
      balance = v;
      return this;
    }

    FilterBuilder min(BigDecimal v) {
      min = v;
      return this;
    }

    FilterBuilder max(BigDecimal v) {
      max = v;
      return this;
    }

    FilterBuilder sort(Sort v) {
      sort = v;
      return this;
    }

    OrderListFilter build() {
      return new OrderListFilter(status, channel, q, from, to, balance, min, max, sort);
    }
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

  private static Spec spec(String number) {
    return new Spec(number);
  }

  /**
   * A seeded row; defaults: PENDING_PAYMENT, ONLINE, 100.00 owed in full, created in seed order.
   */
  private static final class Spec {
    final String number;
    OrderStatus status = OrderStatus.PENDING_PAYMENT;
    OrderChannel channel = OrderChannel.ONLINE;
    OffsetDateTime at;
    BigDecimal total = new BigDecimal("100.00");
    BigDecimal prepaid = BigDecimal.ZERO;
    OffsetDateTime expires;

    Spec(String number) {
      this.number = number;
    }

    Spec status(OrderStatus v) {
      status = v;
      return this;
    }

    Spec channel(OrderChannel v) {
      channel = v;
      return this;
    }

    Spec at(OffsetDateTime v) {
      at = v;
      return this;
    }

    Spec total(String v) {
      total = new BigDecimal(v);
      return this;
    }

    Spec prepaid(String v) {
      prepaid = new BigDecimal(v);
      return this;
    }

    Spec expires(OffsetDateTime v) {
      expires = v;
      return this;
    }
  }

  private UUID seed(UUID org, Spec s) {
    UUID id = UUID.randomUUID();
    OffsetDateTime at = s.at != null ? s.at : AUG_2.plusMinutes(SEQ.getAndIncrement());
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, id)
        .set(SALES_ORDER.ORG_ID, org)
        .set(SALES_ORDER.ORDER_NUMBER, s.number)
        .set(
            SALES_ORDER.CHANNEL,
            com.loai.inventory.repository.generated.enums.OrderChannel.valueOf(s.channel.name()))
        .set(
            SALES_ORDER.STATUS,
            com.loai.inventory.repository.generated.enums.OrderStatus.valueOf(s.status.name()))
        .set(SALES_ORDER.SUBTOTAL, s.total)
        .set(SALES_ORDER.TAX_TOTAL, BigDecimal.ZERO)
        .set(SALES_ORDER.SHIPPING_TOTAL, BigDecimal.ZERO)
        .set(SALES_ORDER.DISCOUNT_TOTAL, BigDecimal.ZERO)
        .set(SALES_ORDER.GRAND_TOTAL, s.total)
        .set(SALES_ORDER.CURRENCY, "EGP")
        .set(SALES_ORDER.PREPAID_AMOUNT, s.prepaid)
        .set(SALES_ORDER.PLACED_AT, at)
        .set(SALES_ORDER.EXPIRES_AT, s.expires)
        .set(SALES_ORDER.CREATED_AT, at)
        .set(SALES_ORDER.UPDATED_AT, at)
        .execute();
    return id;
  }
}
