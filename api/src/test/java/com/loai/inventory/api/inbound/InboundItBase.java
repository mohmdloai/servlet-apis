package com.loai.inventory.api.inbound;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_LOG;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.repository.GoodsReceiptRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryFactoryImpl;
import com.loai.inventory.repository.SupplierRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.repository.generated.tables.records.InventoryLogRecord;
import com.loai.inventory.service.GoodsReceiptService;
import com.loai.inventory.service.SupplierService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The container + service wiring the three inbound ITs share ({@code
 * stories/supplier_goods_receipt.md}). Subclasses declare their own {@code @Container} and call
 * {@link #wire} from {@code @BeforeAll}.
 */
abstract class InboundItBase {

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static SupplierService suppliers;
  static GoodsReceiptService receipts;

  static void wire(PostgreSQLContainer<?> pg) {
    Flyway.configure()
        .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
        .schemas("inventorydb")
        .locations("classpath:db/migration")
        .load()
        .migrate();

    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(pg.getJdbcUrl());
    cfg.setUsername(pg.getUsername());
    cfg.setPassword(pg.getPassword());
    cfg.setMaximumPoolSize(8);
    cfg.setConnectionInitSql("SET search_path TO inventorydb");
    dataSource = new HikariDataSource(cfg);
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);

    suppliers =
        new SupplierService(
            dsl, new SupplierRepositoryFactoryImpl(), new GoodsReceiptRepositoryFactoryImpl());
    receipts =
        new GoodsReceiptService(
            dsl,
            new GoodsReceiptRepositoryFactoryImpl(),
            new SupplierRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new ProductRepositoryFactoryImpl(),
            new UserRepositoryFactoryImpl());
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
        "TRUNCATE goods_receipt_line, goods_receipt, goods_receipt_number_counter, supplier,"
            + " inventory_log, inventory, product, user_org_role, app_user, org"
            + " RESTART IDENTITY CASCADE");
  }

  // fixtures

  static ActorContext actor(UUID userId) {
    return ActorContext.user(userId.toString());
  }

  static SupplierService.SupplierEdit named(String name) {
    return new SupplierService.SupplierEdit(name, null, null, null, null, null);
  }

  static GoodsReceiptService.LineCommand line(UUID product, int qty, String unitCost) {
    return new GoodsReceiptService.LineCommand(product, qty, new BigDecimal(unitCost));
  }

  static GoodsReceiptService.ReceiptCommand delivery(
      UUID supplier, GoodsReceiptService.LineCommand... lines) {
    return new GoodsReceiptService.ReceiptCommand(supplier, null, null, null, List.of(lines));
  }

  static void assertMoney(String expected, BigDecimal actual) {
    assertMoney(expected, actual, null);
  }

  static void assertMoney(String expected, BigDecimal actual, String why) {
    String tail = why == null ? "" : " — " + why;
    assertNotNull(actual, "expected " + expected + " got null" + tail);
    assertEquals(
        0,
        new BigDecimal(expected).compareTo(actual),
        "expected " + expected + " got " + actual + tail);
  }

  UUID createOrg(String slug) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug + "-" + id)
        .execute();
    return id;
  }

  UUID createUser(String email) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, id + "-" + email)
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, ActorType.USER)
        .execute();
    return id;
  }

  UUID createProduct(UUID org, String sku, String costPrice) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, sku + " widget")
        .set(PRODUCT.SKU, sku + "-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .set(PRODUCT.COST_PRICE, costPrice == null ? null : new BigDecimal(costPrice))
        .execute();
    return id;
  }

  UUID createTrackedProduct(UUID org, String sku, String costPrice, int stockQty, int reservedQty) {
    UUID id = createProduct(org, sku, costPrice);
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, org)
        .set(INVENTORY.PRODUCT_ID, id)
        .set(INVENTORY.STOCK_QTY, stockQty)
        .set(INVENTORY.RESERVED_QTY, reservedQty)
        .execute();
    return id;
  }

  int stockOf(UUID product) {
    return dsl.select(INVENTORY.STOCK_QTY)
        .from(INVENTORY)
        .where(INVENTORY.PRODUCT_ID.eq(product))
        .fetchOne(INVENTORY.STOCK_QTY);
  }

  BigDecimal costOf(UUID product) {
    return dsl.select(PRODUCT.COST_PRICE)
        .from(PRODUCT)
        .where(PRODUCT.ID.eq(product))
        .fetchOne(PRODUCT.COST_PRICE);
  }

  List<InventoryLogRecord> logRows(UUID product) {
    return dsl.selectFrom(INVENTORY_LOG)
        .where(INVENTORY_LOG.PRODUCT_ID.eq(product))
        .orderBy(INVENTORY_LOG.ID.asc())
        .fetch();
  }
}
