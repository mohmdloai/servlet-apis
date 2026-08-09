package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.INVENTORY_LOG;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.StockReason;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The first tests in the {@code repository} module (D11).
 *
 * <p>Until now this module had none of its own: its three hardest guarantees were verified only
 * transitively, through {@code api} ITs that assert a business outcome several layers up. That is
 * real coverage, but it is indirect — an IT proves "the order did not oversell", not "the CAS
 * rejected a stale write", so a guarantee that started holding for a different reason (or stopped
 * holding in a case the business flow does not reach) reads as green either way.
 *
 * <p>Deliberately thin. This is not a second home for business rules; it pins exactly the three
 * things the plan names, at the layer that actually implements them:
 *
 * <ol>
 *   <li>the optimistic-lock CAS on stock,
 *   <li>the idempotent restock's {@code ON CONFLICT DO NOTHING} claim,
 *   <li>the {@code PUBLISHED} + {@code org_id} predicates that keep one tenant's drafts private.
 * </ol>
 */
@Testcontainers
class RepositoryGuaranteesIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static DSLContext dsl;
  static InventoryRepositoryImpl inventory;
  static InventoryLogRepositoryImpl inventoryLog;
  static ProductListingRepositoryImpl listings;

  private final AtomicInteger seq = new AtomicInteger();

  @BeforeAll
  static void startInfra() {
    Flyway.configure()
        .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
        .schemas("inventorydb")
        .locations("classpath:db/migration")
        .load()
        .migrate();

    dsl =
        DSL.using(
            PG.getJdbcUrl() + "?currentSchema=inventorydb", PG.getUsername(), PG.getPassword());
    dsl.execute("SET search_path TO inventorydb");
    inventory = new InventoryRepositoryImpl(dsl);
    inventoryLog = new InventoryLogRepositoryImpl(dsl);
    listings = new ProductListingRepositoryImpl(dsl);
  }

  @BeforeEach
  void reset() {
    dsl.execute(
        "TRUNCATE inventory_log, inventory, product_listing, product, org RESTART IDENTITY CASCADE");
  }

  // 1. The optimistic-lock CAS on stock

  /**
   * The whole point of the version column: a writer holding a stale read must lose. Two callers
   * each read version 0 and both try to spend it — the second must be refused, not silently applied
   * on top, which is how a race oversells.
   */
  @Test
  void adjustQuantities_refusesAStaleVersion_andAppliesTheWinnerExactlyOnce() {
    UUID org = seedOrg();
    UUID product = seedProduct(org, "SKU-CAS");
    inventory.insert(new Inventory(org, product, 10, 0, 0L, now()));

    // Both callers read version 0.
    Inventory first = inventory.adjustQuantities(org, product, -4, 0, 0L);
    assertEquals(6, first.getStockQty());
    assertEquals(1L, first.getVersion(), "a successful write moves the version");

    ConflictException refused =
        assertThrows(
            ConflictException.class, () -> inventory.adjustQuantities(org, product, -4, 0, 0L));
    assertTrue(refused.getMessage().contains("version conflict"));

    // The loser changed nothing — not a partial write, not a second decrement.
    assertEquals(6, inventory.findByProductId(org, product).orElseThrow().getStockQty());
  }

  @Test
  void adjustQuantities_isScopedByOrg_soAnotherTenantCannotMoveYourStock() {
    UUID mine = seedOrg();
    UUID theirs = seedOrg();
    UUID product = seedProduct(mine, "SKU-SCOPE");
    inventory.insert(new Inventory(mine, product, 10, 0, 0L, now()));

    assertThrows(
        ConflictException.class,
        () -> inventory.adjustQuantities(theirs, product, -1, 0, 0L),
        "the org predicate is part of the CAS, not a filter applied afterwards");
    assertEquals(10, inventory.findByProductId(mine, product).orElseThrow().getStockQty());
  }

  @Test
  void findByProductId_neverCrossesOrgs() {
    UUID mine = seedOrg();
    UUID theirs = seedOrg();
    UUID product = seedProduct(mine, "SKU-READ");
    inventory.insert(new Inventory(mine, product, 7, 0, 0L, now()));

    assertTrue(inventory.findByProductId(theirs, product).isEmpty());
  }

  // 2. The idempotent restock claim

  /**
   * {@code ON CONFLICT DO NOTHING} is what makes a retried restock safe: the first insert wins the
   * key and returns a row, a replay returns empty, and the caller reads "empty" as "someone already
   * did this — do not move stock again".
   */
  @Test
  void insertIdempotent_returnsTheRowOnceAndEmptyOnEveryReplay() {
    UUID org = seedOrg();
    UUID product = seedProduct(org, "SKU-IDEM");
    inventory.insert(new Inventory(org, product, 0, 0, 0L, now()));

    Optional<?> first = restock(org, product, "restock-key-1");
    assertTrue(first.isPresent(), "the first use claims the key");

    Optional<?> replay = restock(org, product, "restock-key-1");
    assertTrue(replay.isEmpty(), "a replay must be refused, not recorded a second time");

    assertEquals(
        1,
        dsl.fetchCount(INVENTORY_LOG, INVENTORY_LOG.IDEMPOTENCY_KEY.eq("restock-key-1")),
        "exactly one ledger row exists for the key");
  }

  @Test
  void insertIdempotent_treatsADifferentKeyAsDifferentWork() {
    UUID org = seedOrg();
    UUID product = seedProduct(org, "SKU-IDEM2");
    inventory.insert(new Inventory(org, product, 0, 0, 0L, now()));

    assertTrue(restock(org, product, "key-a").isPresent());
    assertTrue(restock(org, product, "key-b").isPresent());
    assertEquals(2, dsl.fetchCount(INVENTORY_LOG));
  }

  /** The key is claimed per org, so two tenants may legitimately use the same string. */
  @Test
  void insertIdempotent_scopesTheKeyToTheOrg() {
    UUID a = seedOrg();
    UUID b = seedOrg();
    UUID productA = seedProduct(a, "SKU-A");
    UUID productB = seedProduct(b, "SKU-B");
    inventory.insert(new Inventory(a, productA, 0, 0, 0L, now()));
    inventory.insert(new Inventory(b, productB, 0, 0, 0L, now()));

    assertTrue(restock(a, productA, "shared-key").isPresent());
    assertTrue(
        restock(b, productB, "shared-key").isPresent(),
        "one tenant's idempotency key must not consume another's");
  }

  @Test
  void findByIdempotencyKey_isOrgScoped() {
    UUID a = seedOrg();
    UUID b = seedOrg();
    UUID product = seedProduct(a, "SKU-FIND");
    inventory.insert(new Inventory(a, product, 0, 0, 0L, now()));
    restock(a, product, "only-mine");

    assertTrue(inventoryLog.findByIdempotencyKey(a, "only-mine").isPresent());
    assertTrue(inventoryLog.findByIdempotencyKey(b, "only-mine").isEmpty());
  }

  // 3. The PUBLISHED + org_id predicates

  /**
   * The storefront's confidentiality rule, at the layer that enforces it: a DRAFT or ARCHIVED
   * listing is not reachable through a published read, and neither is another tenant's PUBLISHED
   * one. Both predicates live in the same WHERE, so this pins that neither can be dropped alone.
   */
  @Test
  void findPublishedByIds_servesOnlyPublishedRowsOfTheAskingOrg() {
    UUID mine = seedOrg();
    UUID theirs = seedOrg();
    UUID published = seedListing(mine, "live", ListingStatus.PUBLISHED);
    UUID draft = seedListing(mine, "draft", ListingStatus.DRAFT);
    UUID archived = seedListing(mine, "old", ListingStatus.ARCHIVED);
    UUID foreign = seedListing(theirs, "theirs", ListingStatus.PUBLISHED);

    List<ProductListing> found =
        listings.findPublishedByIds(mine, List.of(published, draft, archived, foreign));

    assertEquals(1, found.size(), "only the org's own PUBLISHED row crosses");
    assertEquals(published, found.get(0).getId());
  }

  @Test
  void findByIds_isStillOrgScoped_eventhoughItServesEveryStatus() {
    // The admin-plane read: status is deliberately unfiltered, but the tenant boundary is not.
    UUID mine = seedOrg();
    UUID theirs = seedOrg();
    UUID draft = seedListing(mine, "draft", ListingStatus.DRAFT);
    UUID foreign = seedListing(theirs, "theirs", ListingStatus.PUBLISHED);

    List<ProductListing> found = listings.findByIds(mine, List.of(draft, foreign));

    assertEquals(1, found.size());
    assertEquals(draft, found.get(0).getId(), "an admin read sees drafts — but only its own");
  }

  @Test
  void findPublishedByIds_onAnEmptyRequestDoesNotQueryAtAll() {
    assertTrue(listings.findPublishedByIds(seedOrg(), List.of()).isEmpty());
  }

  // seeding

  private Optional<?> restock(UUID org, UUID product, String key) {
    return inventoryLog.insertIdempotent(
        org, product, 5, 0, 5, 0, StockReason.RESTOCK, null, ActorContext.user("tester"), key);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private UUID seedOrg() {
    UUID id = UUID.randomUUID();
    int n = seq.incrementAndGet();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, "Org " + n)
        .set(ORG.SLUG, "org-" + n + "-" + id.toString().substring(0, 8))
        .execute();
    return id;
  }

  private UUID seedProduct(UUID orgId, String sku) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, orgId)
        .set(PRODUCT.NAME, "Product " + sku)
        .set(PRODUCT.SKU, sku + "-" + seq.incrementAndGet())
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .execute();
    return id;
  }

  private UUID seedListing(UUID orgId, String slug, ListingStatus status) {
    UUID product = seedProduct(orgId, "L-" + slug);
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT_LISTING)
        .set(PRODUCT_LISTING.ID, id)
        .set(PRODUCT_LISTING.ORG_ID, orgId)
        .set(PRODUCT_LISTING.PRODUCT_ID, product)
        .set(PRODUCT_LISTING.SLUG, slug + "-" + seq.incrementAndGet())
        .set(PRODUCT_LISTING.SALES_PRICE, new BigDecimal("12.00"))
        .set(
            PRODUCT_LISTING.STATUS,
            com.loai.inventory.repository.generated.enums.ListingStatus.valueOf(status.name()))
        .execute();
    return id;
  }
}
