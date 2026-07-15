package com.loai.inventory.api.portal;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.CustomerAddress;
import com.loai.inventory.repository.CustomerAddressRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.service.CustomerPortalService;
import com.loai.inventory.service.CustomerPortalService.AddressInput;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
 * Portal saved-address book (slice P4, {@code stories/portal_addresses_reorder.md}) end-to-end
 * against real Postgres, driving the {@link CustomerPortalService} address methods behind {@code
 * GET|POST /api/portal/addresses}, {@code PATCH|DELETE /addresses/{id}} and {@code POST
 * /addresses/{id}/default}. Covers acceptance criterion 1: address CRUD is customer-scoped; exactly
 * one {@code is_default} per customer (setting a new default clears the old, atomically); a delete
 * removes only the caller's own row; and a foreign/unknown id is the same opaque 404.
 */
@Testcontainers
class PortalAddressesIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static CustomerPortalService portalService;

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

    portalService =
        new CustomerPortalService(
            dsl,
            new CustomerRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new SalesInvoiceRepositoryFactoryImpl(),
            new CustomerAddressRepositoryFactoryImpl(),
            new ProductListingRepositoryFactoryImpl());
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute("TRUNCATE customer_address, customer, org RESTART IDENTITY CASCADE");
  }

  private static AddressInput input(String label, String address, boolean makeDefault) {
    return new AddressInput(label, "Nadia", "+20100", address, makeDefault);
  }

  // AC1: the first address auto-defaults; promotion clears the old default atomically

  @Test
  void firstAddressAutoDefaults_andPromotionClearsThePrevious() {
    UUID org = createOrg();
    UUID cust = createCustomer(org, "a@acme.test");

    CustomerAddress home =
        portalService.createAddress(org, cust, input("Home", "1 Nile St", false));
    assertTrue(home.isDefault(), "the customer's first saved address becomes their default");

    CustomerAddress work =
        portalService.createAddress(org, cust, input("Work", "2 Tahrir Sq", false));
    assertFalse(work.isDefault(), "a later address is not default unless asked");

    // Exactly one default, and it is Home.
    assertEquals(1, defaultCount(org, cust));
    List<CustomerAddress> list = portalService.listAddresses(org, cust);
    assertEquals(2, list.size());
    assertEquals("Home", list.get(0).getLabel(), "the default sorts first");
    assertTrue(list.get(0).isDefault());

    // Promote Work → Home is cleared, still exactly one default.
    CustomerAddress promoted = portalService.setDefaultAddress(org, cust, work.getId());
    assertTrue(promoted.isDefault());
    assertEquals(1, defaultCount(org, cust), "still exactly one default after promotion");
    assertEquals(
        work.getId(),
        portalService.listAddresses(org, cust).get(0).getId(),
        "Work is now the default and sorts first");
  }

  @Test
  void createWithMakeDefault_promotesImmediately() {
    UUID org = createOrg();
    UUID cust = createCustomer(org, "a@acme.test");
    CustomerAddress home =
        portalService.createAddress(org, cust, input("Home", "1 Nile St", false));
    CustomerAddress work =
        portalService.createAddress(org, cust, input("Work", "2 Tahrir Sq", true));

    assertTrue(work.isDefault(), "created with makeDefault → this address is the default");
    assertTrue(reload(org, work.getId(), cust).isDefault(), "and it persisted as the default");
    assertEquals(1, defaultCount(org, cust));
    assertFalse(reload(org, home.getId(), cust).isDefault(), "Home was demoted");
  }

  // AC1: update edits content; delete removes only the caller's own row

  @Test
  void updateEditsContent_deleteRemovesTheRow() {
    UUID org = createOrg();
    UUID cust = createCustomer(org, "a@acme.test");
    CustomerAddress a = portalService.createAddress(org, cust, input("Home", "1 Nile St", false));

    CustomerAddress edited =
        portalService.updateAddress(org, cust, a.getId(), input("Home 2", "9 New St", false));
    assertEquals("Home 2", edited.getLabel());
    assertEquals("9 New St", edited.getAddress());
    assertTrue(edited.isDefault(), "editing content never demotes the default");

    portalService.deleteAddress(org, cust, a.getId());
    assertTrue(portalService.listAddresses(org, cust).isEmpty());
  }

  @Test
  void addressRequiresANonBlankAddressLine() {
    UUID org = createOrg();
    UUID cust = createCustomer(org, "a@acme.test");
    assertThrows(
        ValidationException.class,
        () -> portalService.createAddress(org, cust, input("Home", "   ", false)));
  }

  // AC1: every operation is (org, customer)-scoped — a foreign id is an opaque 404

  @Test
  void foreignCustomerCannotReadEditDeleteOrPromoteAnothersAddress() {
    UUID org = createOrg();
    UUID custA = createCustomer(org, "a@acme.test");
    UUID custB = createCustomer(org, "b@acme.test");
    CustomerAddress a = portalService.createAddress(org, custA, input("Home", "1 Nile St", false));

    // B's book never contains A's row.
    assertTrue(portalService.listAddresses(org, custB).isEmpty());

    // Every by-id op from B is the same opaque 404.
    assertThrows(
        NotFoundException.class,
        () -> portalService.updateAddress(org, custB, a.getId(), input("x", "y", false)));
    assertThrows(NotFoundException.class, () -> portalService.deleteAddress(org, custB, a.getId()));
    assertThrows(
        NotFoundException.class, () -> portalService.setDefaultAddress(org, custB, a.getId()));

    // And A's row is untouched.
    assertEquals(1, portalService.listAddresses(org, custA).size());
  }

  @Test
  void unknownIdIsTheSame404() {
    UUID org = createOrg();
    UUID cust = createCustomer(org, "a@acme.test");
    assertThrows(
        NotFoundException.class,
        () -> portalService.setDefaultAddress(org, cust, UUID.randomUUID()));
    assertThrows(
        NotFoundException.class, () -> portalService.deleteAddress(org, cust, UUID.randomUUID()));
  }

  @Test
  void bookIsOrgScoped_sameCustomerIdInAnotherOrgSeesNothing() {
    UUID orgA = createOrg();
    UUID orgB = createOrg();
    UUID cust = createCustomer(orgA, "a@acme.test");
    CustomerAddress a = portalService.createAddress(orgA, cust, input("Home", "1 Nile St", false));

    assertTrue(portalService.listAddresses(orgB, cust).isEmpty());
    assertThrows(NotFoundException.class, () -> portalService.deleteAddress(orgB, cust, a.getId()));
  }

  // helpers

  private CustomerAddress reload(UUID org, UUID id, UUID cust) {
    return portalService.listAddresses(org, cust).stream()
        .filter(x -> x.getId().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private long defaultCount(UUID org, UUID cust) {
    return portalService.listAddresses(org, cust).stream()
        .filter(CustomerAddress::isDefault)
        .count();
  }

  private UUID createOrg() {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, "Store")
        .set(ORG.SLUG, "store-" + id)
        .execute();
    return id;
  }

  private UUID createCustomer(UUID orgId, String email) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, orgId)
        .set(CUSTOMER.NAME, "Cust")
        .set(CUSTOMER.EMAIL, email)
        .execute();
    return id;
  }
}
