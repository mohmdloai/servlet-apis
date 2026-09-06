package com.loai.inventory.api.expiry;

import static com.loai.inventory.repository.generated.Tables.CATEGORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_CATEGORY;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_VARIANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.domain.model.InventoryListFilter;
import com.loai.inventory.domain.model.InventoryListFilter.ReorderRule;
import com.loai.inventory.domain.model.InventoryListFilter.Sort;
import com.loai.inventory.domain.model.InventoryListStats;
import com.loai.inventory.domain.model.InventoryStockCounts;
import com.loai.inventory.domain.model.InventoryStockFilter;
import com.loai.inventory.domain.repository.InventoryRepository.OverviewRow;
import com.loai.inventory.repository.ProductRepositoryImpl;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.service.InventoryService;
import com.loai.inventory.service.InventoryService.OverviewPage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Integration coverage for the stock overview's filter dimensions ({@code
 * stories/inventory_filters.md}) against real PostgreSQL: the upgraded {@code q} (folded name, SKU,
 * exact barcode), category through the listing (own or variant), holds, the reorder rule, the
 * changed window, the explicit sorts, the stock summary and the tab counts — every one from the one
 * predicate the rows use. Reuses {@link ExpiryIntegrationTestBase} for the container + seeding.
 */
class InventoryFiltersIT extends ExpiryIntegrationTestBase {

  private InventoryService service() {
    return new InventoryService(
        dsl,
        inventoryRepoFactory,
        inventoryLogRepoFactory,
        reservationRepoFactory,
        new ProductRepositoryImpl(dsl),
        salesOrderRepoFactory);
  }

  private static InventoryListFilter f(String q) {
    return InventoryListFilter.of(q, null, null);
  }

  private static InventoryListFilter none() {
    return InventoryListFilter.none();
  }

  private static List<UUID> ids(OverviewPage page) {
    return page.rows().stream().map(OverviewRow::productId).toList();
  }

  private OverviewPage list(UUID org, InventoryListFilter filter) {
    return service().listOverview(org, filter, 0, 50);
  }

  // q — name (folded), SKU, exact barcode

  @Test
  void q_matchesFoldedArabicName_sku_andExactBarcode_neverAPartialBarcode() {
    UUID org = createOrg("q");
    UUID sugar = createProduct(org, "SUG-001");
    UUID beans = createProduct(org, "BNS-001");
    UUID salt = createProduct(org, "SLT-001");
    dsl.update(PRODUCT)
        .set(PRODUCT.NAME, "سُكّر أبيض")
        .set(PRODUCT.BARCODE, "6221033100013")
        .where(PRODUCT.ID.eq(sugar))
        .execute();
    dsl.update(PRODUCT).set(PRODUCT.NAME, "Beans 1kg").where(PRODUCT.ID.eq(beans)).execute();
    dsl.update(PRODUCT).set(PRODUCT.NAME, "Salt 500g").where(PRODUCT.ID.eq(salt)).execute();

    // The plain spelling finds the diacritised name through the generated name_search key.
    assertEquals(List.of(sugar), ids(list(org, f("سكر"))));
    // SKU, case-insensitively, as before.
    assertEquals(List.of(beans), ids(list(org, f("bns"))));
    // The whole barcode lands on its one product…
    assertEquals(List.of(sugar), ids(list(org, f("6221033100013"))));
    // …and a fragment of it is not a barcode match (nor a name/SKU one) — no false hits.
    assertEquals(List.of(), ids(list(org, f("622103310001"))));
    // Blank q is no filter.
    assertEquals(3, list(org, f("   ")).total());
  }

  // category — through the listing, own or variant

  @Test
  void category_selectsTheListingsProduct_andItsVariantChildren_neverAnUnlistedProduct() {
    UUID org = createOrg("cat");
    UUID food = createCategory(org, "food");
    UUID drinks = createCategory(org, "drinks");
    UUID beans = createProduct(org, "BNS");
    UUID beansLarge = createProduct(org, "BNS-L"); // a variant child of the beans listing
    UUID tea = createProduct(org, "TEA");
    UUID salt = createProduct(org, "SLT"); // no listing → in no category
    UUID beansListing = createListing(org, beans, food);
    createVariant(org, beansListing, beansLarge);
    createListing(org, tea, drinks);
    createInventory(org, salt, 5);

    InventoryListFilter inFood =
        new InventoryListFilter(null, null, null, food, null, null, null, null, null);
    InventoryListFilter inDrinks =
        new InventoryListFilter(null, null, null, drinks, null, null, null, null, null);

    assertEquals(sorted(List.of(beans, beansLarge)), sorted(ids(list(org, inFood))));
    assertEquals(List.of(tea), ids(list(org, inDrinks)));
    // Composes with the tab: untracked rows in Food = both beans rows (no inventory), never salt.
    InventoryListFilter untrackedFood =
        new InventoryListFilter(
            null, InventoryStockFilter.UNTRACKED, null, food, null, null, null, null, null);
    assertEquals(sorted(List.of(beans, beansLarge)), sorted(ids(list(org, untrackedFood))));
    // A category from another org selects nothing here.
    UUID other = createOrg("cat-other");
    UUID otherFood = createCategory(other, "food");
    assertEquals(
        0,
        list(
                org,
                new InventoryListFilter(null, null, null, otherFood, null, null, null, null, null))
            .total());
  }

  // held — reserved_qty > 0 / = 0; untracked rows fall out

  @Test
  void held_partitionsTrackedRowsByReservedQty_andDropsUntracked() {
    UUID org = createOrg("held");
    UUID held = createProduct(org, "HELD");
    UUID free = createProduct(org, "FREE");
    UUID untracked = createProduct(org, "UNTRACKED");
    createInventory(org, held, 10);
    createInventory(org, free, 10);
    seedOrder(org, OrderStatus.PENDING_PAYMENT, minutesAhead(30), List.of(new Line(held, 3)));

    assertEquals(List.of(held), ids(list(org, heldFilter(true))));
    assertEquals(List.of(free), ids(list(org, heldFilter(false))));
    assertTrue(
        !ids(list(org, heldFilter(false))).contains(untracked),
        "an untracked product has no reserved figure and must not pass held=false");
  }

  private static InventoryListFilter heldFilter(boolean held) {
    return new InventoryListFilter(null, null, null, null, held, null, null, null, null);
  }

  // rule — reorder point set / none; product-side, so untracked rows take part

  @Test
  void rule_partitionsByReorderPoint_includingUntrackedProducts() {
    UUID org = createOrg("rule");
    UUID ruled = createProduct(org, "RULED");
    UUID ruledUntracked = createProduct(org, "RULED-UNTRACKED");
    UUID unruled = createProduct(org, "UNRULED");
    createInventory(org, ruled, 4);
    createInventory(org, unruled, 4);
    setReorderPoint(ruled, 5);
    setReorderPoint(ruledUntracked, 5);

    InventoryListFilter set =
        new InventoryListFilter(null, null, null, null, null, ReorderRule.SET, null, null, null);
    InventoryListFilter noneSet =
        new InventoryListFilter(null, null, null, null, null, ReorderRule.NONE, null, null, null);
    assertEquals(sorted(List.of(ruled, ruledUntracked)), sorted(ids(list(org, set))));
    assertEquals(List.of(unruled), ids(list(org, noneSet)));
    // Composes with the REORDER tab: the tab already implies SET; NONE + REORDER is empty.
    assertEquals(
        0,
        list(
                org,
                new InventoryListFilter(
                    null,
                    InventoryStockFilter.REORDER,
                    null,
                    null,
                    null,
                    ReorderRule.NONE,
                    null,
                    null,
                    null))
            .total());
  }

  // changed window — half-open on inventory.updated_at; untracked rows fall out

  @Test
  void changedWindow_isHalfOpenOnUpdatedAt_andDropsUntracked() {
    UUID org = createOrg("changed");
    UUID fresh = createProduct(org, "FRESH");
    UUID stale = createProduct(org, "STALE");
    UUID untracked = createProduct(org, "UNTRACKED");
    createInventory(org, fresh, 10);
    createInventory(org, stale, 10);
    OffsetDateTime now = OffsetDateTime.now();
    stampUpdatedAt(org, fresh, now.minusDays(2));
    stampUpdatedAt(org, stale, now.minusDays(120));

    OffsetDateTime cut30 = now.minusDays(30);
    // Past 30 days: [cut30, ∞)
    assertEquals(List.of(fresh), ids(list(org, changed(cut30, null))));
    // Over 30 days ago: (-∞, cut30)
    assertEquals(List.of(stale), ids(list(org, changed(null, cut30))));
    // A row stamped exactly at the boundary belongs to the FROM side, not the TO side.
    stampUpdatedAt(org, stale, cut30);
    assertEquals(sorted(List.of(fresh, stale)), sorted(ids(list(org, changed(cut30, null)))));
    assertEquals(List.of(), ids(list(org, changed(null, cut30))));
    assertTrue(!ids(list(org, changed(null, now.plusDays(1)))).contains(untracked));
  }

  private static InventoryListFilter changed(OffsetDateTime from, OffsetDateTime to) {
    return new InventoryListFilter(null, null, null, null, null, null, from, to, null);
  }

  // sort — the four explicit orders; untracked last on the stock-driven ones

  @Test
  void sort_ordersByAvailable_onHand_orUpdated_withUntrackedLast_andNameByDefault() {
    UUID org = createOrg("sort");
    UUID a = createProduct(org, "A-plenty"); // on hand 50, 10 held → 40 available
    UUID b = createProduct(org, "B-scarce"); // on hand 3 → 3 available
    UUID c = createProduct(org, "C-mid"); // on hand 20 → 20 available
    UUID d = createProduct(org, "D-untracked");
    createInventory(org, a, 50);
    createInventory(org, b, 3);
    createInventory(org, c, 20);
    seedOrder(org, OrderStatus.PENDING_PAYMENT, minutesAhead(30), List.of(new Line(a, 10)));
    OffsetDateTime now = OffsetDateTime.now();
    stampUpdatedAt(org, a, now.minusDays(10));
    stampUpdatedAt(org, b, now.minusDays(1));
    stampUpdatedAt(org, c, now.minusDays(5));

    assertEquals(List.of(a, b, c, d), ids(list(org, none())), "default = name ASC");
    assertEquals(List.of(a, b, c, d), ids(list(org, sort(Sort.NAME))));
    assertEquals(List.of(b, c, a, d), ids(list(org, sort(Sort.AVAILABLE))), "3, 20, 40, untracked");
    assertEquals(List.of(a, c, b, d), ids(list(org, sort(Sort.ON_HAND))), "50, 20, 3, untracked");
    assertEquals(List.of(b, c, a, d), ids(list(org, sort(Sort.UPDATED))), "1d, 5d, 10d, untracked");
  }

  @Test
  void sort_onTheReorderTab_overridesTheQueueOrder_onlyWhenAsked() {
    UUID org = createOrg("sort-reorder");
    UUID deep = createProduct(org, "Zed-deep"); // 0 of 10 → -10 below, but name sorts last
    UUID shallow = createProduct(org, "Alpha-shallow"); // 4 of 5 → -1 below, name first
    createInventory(org, deep, 0);
    createInventory(org, shallow, 4);
    setReorderPoint(deep, 10);
    setReorderPoint(shallow, 5);

    InventoryListFilter tab =
        new InventoryListFilter(
            null, InventoryStockFilter.REORDER, null, null, null, null, null, null, null);
    assertEquals(
        List.of(deep, shallow), ids(list(org, tab)), "queue: deepest below its point first");
    InventoryListFilter tabByName =
        new InventoryListFilter(
            null, InventoryStockFilter.REORDER, null, null, null, null, null, null, Sort.NAME);
    assertEquals(List.of(shallow, deep), ids(list(org, tabByName)), "an explicit sort wins");
  }

  private static InventoryListFilter sort(Sort sort) {
    return new InventoryListFilter(null, null, null, null, null, null, null, null, sort);
  }

  // summary — same predicate as the rows

  @Test
  void summary_countsEveryRow_sumsTrackedUnits_andValuesCostedRowsOnly() {
    UUID org = createOrg("summary");
    UUID costed = createProduct(org, "COSTED"); // 30 on hand, 4 held, cost 12.50
    UUID uncosted = createProduct(org, "UNCOSTED"); // 14 on hand, no cost
    UUID untracked = createProduct(org, "UNTRACKED");
    createInventory(org, costed, 30);
    createInventory(org, uncosted, 14);
    seedOrder(org, OrderStatus.PENDING_PAYMENT, minutesAhead(30), List.of(new Line(costed, 4)));
    dsl.update(PRODUCT)
        .set(PRODUCT.COST_PRICE, new BigDecimal("12.50"))
        .where(PRODUCT.ID.eq(costed))
        .execute();

    OverviewPage all = list(org, none());
    InventoryListStats s = all.stats();
    assertEquals(3, all.total(), "the pager's total is the summary's product count");
    assertEquals(3, s.products());
    assertEquals(44, s.unitsOnHand());
    assertEquals(40, s.unitsAvailable());
    assertEquals(1, s.costedProducts());
    assertEquals(new BigDecimal("375.00"), s.costValue(), "30 × 12.50; the uncosted row adds 0");

    // Narrowed the same way the rows are: q hits the uncosted row only.
    InventoryListStats narrowed = list(org, f("uncosted")).stats();
    assertEquals(1, narrowed.products());
    assertEquals(14, narrowed.unitsOnHand());
    assertEquals(0, narrowed.costedProducts());
    assertEquals(new BigDecimal("0.00"), narrowed.costValue());
    // The untracked-only view: one product, no units, no cost.
    InventoryListStats unt =
        list(
                org,
                new InventoryListFilter(
                    null, InventoryStockFilter.UNTRACKED, null, null, null, null, null, null, null))
            .stats();
    assertEquals(1, unt.products());
    assertEquals(0, unt.unitsOnHand());
    assertTrue(ids(list(org, none())).contains(untracked));
  }

  // stock counts — the tabs' numbers equal each tab's total

  @Test
  void stockCounts_equalEachTabsTotal_atTheGivenLowBound() {
    UUID org = createOrg("counts");
    UUID healthy = createProduct(org, "HEALTHY");
    UUID out = createProduct(org, "OUT");
    UUID low = createProduct(org, "LOW");
    UUID reorder = createProduct(org, "REORDER");
    UUID untracked = createProduct(org, "UNTRACKED");
    createInventory(org, healthy, 100);
    createInventory(org, out, 0);
    createInventory(org, low, 3);
    createInventory(org, reorder, 8);
    setReorderPoint(reorder, 8);

    InventoryStockCounts c = service().stockCounts(org, 3);
    assertEquals(5, c.all());
    assertEquals(2, c.low(), "OUT (0) and LOW (3) are at or below 3");
    assertEquals(1, c.reorder());
    assertEquals(1, c.out());
    assertEquals(1, c.untracked());
    assertTrue(ids(list(org, none())).contains(untracked));

    // Each count is exactly the matching tab's total (same arms, same rows).
    for (InventoryStockFilter tab : InventoryStockFilter.values()) {
      long total =
          list(org, new InventoryListFilter(null, tab, 3, null, null, null, null, null, null))
              .total();
      long count =
          switch (tab) {
            case LOW -> c.low();
            case REORDER -> c.reorder();
            case OUT -> c.out();
            case UNTRACKED -> c.untracked();
            case TRACKED -> c.all() - c.untracked();
          };
      assertEquals(total, count, tab + " chip must equal its list's total");
    }
    // The bound is the caller's: at 5 the healthy row still stays out, REORDER (8) too.
    assertEquals(2, service().stockCounts(org, 5).low());
    // The default (null) bound is 5, the LOW tab's own default.
    assertEquals(2, service().stockCounts(org, null).low());
  }

  // seeding helpers

  private UUID createCategory(UUID orgId, String slug) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CATEGORY)
        .set(CATEGORY.ID, id)
        .set(CATEGORY.ORG_ID, orgId)
        .set(CATEGORY.SLUG, slug + "-" + id)
        .execute();
    return id;
  }

  private UUID createListing(UUID orgId, UUID productId, UUID categoryId) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT_LISTING)
        .set(PRODUCT_LISTING.ID, id)
        .set(PRODUCT_LISTING.ORG_ID, orgId)
        .set(PRODUCT_LISTING.PRODUCT_ID, productId)
        .set(PRODUCT_LISTING.SLUG, "listing-" + id)
        .set(PRODUCT_LISTING.SALES_PRICE, new BigDecimal("12.00"))
        .execute();
    dsl.insertInto(PRODUCT_LISTING_CATEGORY)
        .set(PRODUCT_LISTING_CATEGORY.LISTING_ID, id)
        .set(PRODUCT_LISTING_CATEGORY.CATEGORY_ID, categoryId)
        .execute();
    return id;
  }

  private void createVariant(UUID orgId, UUID listingId, UUID childProductId) {
    dsl.insertInto(PRODUCT_VARIANT)
        .set(PRODUCT_VARIANT.ID, UUID.randomUUID())
        .set(PRODUCT_VARIANT.ORG_ID, orgId)
        .set(PRODUCT_VARIANT.PRODUCT_LISTING_ID, listingId)
        .set(PRODUCT_VARIANT.PRODUCT_ID, childProductId)
        .set(PRODUCT_VARIANT.VARIANT_KEY, "large")
        .set(PRODUCT_VARIANT.SALES_PRICE, new BigDecimal("15.00"))
        .execute();
  }

  private void setReorderPoint(UUID productId, int point) {
    dsl.update(PRODUCT).set(PRODUCT.REORDER_POINT, point).where(PRODUCT.ID.eq(productId)).execute();
  }

  private void stampUpdatedAt(UUID orgId, UUID productId, OffsetDateTime at) {
    dsl.update(INVENTORY)
        .set(INVENTORY.UPDATED_AT, at)
        .where(INVENTORY.ORG_ID.eq(orgId).and(INVENTORY.PRODUCT_ID.eq(productId)))
        .execute();
  }

  private static List<UUID> sorted(List<UUID> ids) {
    return ids.stream().sorted().toList();
  }
}
