package com.loai.inventory.api.order;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.api.mapper.SalesOrderMapper;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.repository.SalesOrderRepository;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * {@code ?channel=} on the order worklist ({@code stories/counter_return.md}): one more predicate,
 * AND-composed with {@code ?status=}, the queue-vs-ledger ordering still keyed on status alone; an
 * unknown value is a 400 naming the three channels (the {@code ?status=} pattern).
 */
@Testcontainers
class OrderChannelFilterIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static SalesOrderRepository repo;

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

  @BeforeEach
  void freshSchema() {
    dsl.execute("TRUNCATE sales_order, org RESTART IDENTITY CASCADE");
  }

  @Test
  void channel_narrowsTheList_andComposesWithStatus() {
    UUID org = createOrg("acme");
    UUID other = createOrg("other");
    seedOrder(org, "SO-2026-00001", OrderChannel.IN_STORE, OrderStatus.CLOSED, 1);
    seedOrder(org, "SO-2026-00002", OrderChannel.IN_STORE, OrderStatus.CLOSED, 2);
    seedOrder(org, "SO-2026-00003", OrderChannel.ONLINE, OrderStatus.CLOSED, 3);
    seedOrder(org, "SO-2026-00004", OrderChannel.IN_STORE, OrderStatus.CANCELLED, 4);
    seedOrder(other, "SO-2026-00001", OrderChannel.IN_STORE, OrderStatus.CLOSED, 5);

    // channel alone
    assertEquals(3, repo.count(org, null, OrderChannel.IN_STORE));
    assertEquals(
        List.of("SO-2026-00004", "SO-2026-00002", "SO-2026-00001"),
        repo.list(org, null, OrderChannel.IN_STORE, 0, 10).stream()
            .map(o -> o.getOrderNumber())
            .toList(),
        "no status → the ledger, newest first");

    // channel + status: the day's counter sales, oldest first (a queue)
    assertEquals(2, repo.count(org, OrderStatus.CLOSED, OrderChannel.IN_STORE));
    assertEquals(
        List.of("SO-2026-00001", "SO-2026-00002"),
        repo.list(org, OrderStatus.CLOSED, OrderChannel.IN_STORE, 0, 10).stream()
            .map(o -> o.getOrderNumber())
            .toList());

    // no channel → unchanged reads
    assertEquals(4, repo.count(org, null));
    assertEquals(4, repo.count(org, null, null));
    assertEquals(3, repo.count(org, OrderStatus.CLOSED, null));
  }

  @Test
  void unknownChannel_is400NamingTheThree() {
    ValidationException e =
        assertThrows(ValidationException.class, () -> SalesOrderMapper.toOrderChannel("KIOSK"));
    assertEquals(
        "Unknown order channel: KIOSK (expected ONLINE, PHONE or IN_STORE)", e.getMessage());
    assertEquals(OrderChannel.IN_STORE, SalesOrderMapper.toOrderChannel(" in_store "));
    assertEquals(null, SalesOrderMapper.toOrderChannel(""));
    assertEquals(null, SalesOrderMapper.toOrderChannel(null));
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

  private void seedOrder(
      UUID org, String number, OrderChannel channel, OrderStatus status, int minuteOffset) {
    OffsetDateTime at = OffsetDateTime.of(2026, 8, 27, 9, minuteOffset, 0, 0, ZoneOffset.UTC);
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, UUID.randomUUID())
        .set(SALES_ORDER.ORG_ID, org)
        .set(SALES_ORDER.ORDER_NUMBER, number)
        .set(
            SALES_ORDER.CHANNEL,
            com.loai.inventory.repository.generated.enums.OrderChannel.valueOf(channel.name()))
        .set(
            SALES_ORDER.STATUS,
            com.loai.inventory.repository.generated.enums.OrderStatus.valueOf(status.name()))
        .set(SALES_ORDER.SUBTOTAL, new BigDecimal("10.00"))
        .set(SALES_ORDER.TAX_TOTAL, BigDecimal.ZERO)
        .set(SALES_ORDER.SHIPPING_TOTAL, BigDecimal.ZERO)
        .set(SALES_ORDER.DISCOUNT_TOTAL, BigDecimal.ZERO)
        .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal("10.00"))
        .set(SALES_ORDER.CURRENCY, "EGP")
        .set(SALES_ORDER.PREPAID_AMOUNT, BigDecimal.ZERO)
        .set(SALES_ORDER.CREATED_AT, at)
        .set(SALES_ORDER.UPDATED_AT, at)
        .execute();
  }
}
