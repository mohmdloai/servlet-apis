package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.GoodsReceipt;
import com.loai.inventory.domain.model.GoodsReceiptLine;
import com.loai.inventory.domain.model.GoodsReceiptListFilter;
import com.loai.inventory.domain.model.GoodsReceiptStatus;
import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.domain.model.InventoryLog;
import com.loai.inventory.domain.model.Product;
import com.loai.inventory.domain.model.StockReason;
import com.loai.inventory.domain.model.Supplier;
import com.loai.inventory.domain.repository.GoodsReceiptRepository;
import com.loai.inventory.domain.repository.GoodsReceiptRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryLogRepository;
import com.loai.inventory.domain.repository.InventoryLogRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryRepository;
import com.loai.inventory.domain.repository.InventoryRepositoryFactory;
import com.loai.inventory.domain.repository.ProductRepository;
import com.loai.inventory.domain.repository.ProductRepositoryFactory;
import com.loai.inventory.domain.repository.SupplierRepositoryFactory;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Goods receipts ({@code stories/supplier_goods_receipt.md}) — inbound slice 1.
 *
 * <pre>
 * post(receipt):  number ← claim(org, year)          -- FOR UPDATE, rolls back with the txn
 *                 header + lines ← insert
 *                 ∀ line:  lock(inventory) → +qty
 *                          log(RESTOCK, unit_cost = line.cost, goods_receipt_id = receipt)
 *                          product.cost_price ← line.cost          -- LAST cost, not average
 * void(receipt):  ∀ line:  lock(inventory) → −qty   (409 if it would cross reserved_qty)
 *                          log(RESTOCK, −qty, same cost, same receipt)
 *                 status ← VOIDED                    -- cost_price is NOT restored
 * </pre>
 *
 * <p>Two decisions worth not re-deriving. <b>The receipt posts no journal entry:</b> V101's {@code
 * STOCK/MOVED} already posts each line's {@code inventory_log} row (DR 1200 / CR 2000), so a {@code
 * GOODS_RECEIPT} template would count one event twice. <b>Last cost, not a moving average:</b> COGS
 * freezes per sale line at placement, so an average would mean revaluing stock on hand and
 * re-costing open lines — a different machine.
 */
public class GoodsReceiptService {
  private static final Logger log = LoggerFactory.getLogger(GoodsReceiptService.class);

  public static final int MAX_LINES = 200;
  public static final int SUPPLIER_REFERENCE_MAX = 100;
  public static final int VOID_REASON_MAX = 200;
  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  private static final int MONEY_SCALE = 2;

  private final DSLContext rootDsl;
  private final GoodsReceiptRepositoryFactory receiptRepoFactory;
  private final SupplierRepositoryFactory supplierRepoFactory;
  private final InventoryRepositoryFactory inventoryRepoFactory;
  private final InventoryLogRepositoryFactory inventoryLogRepoFactory;
  private final ProductRepositoryFactory productRepoFactory;
  private final UserRepositoryFactory userRepoFactory;

  public GoodsReceiptService(
      DSLContext rootDsl,
      GoodsReceiptRepositoryFactory receiptRepoFactory,
      SupplierRepositoryFactory supplierRepoFactory,
      InventoryRepositoryFactory inventoryRepoFactory,
      InventoryLogRepositoryFactory inventoryLogRepoFactory,
      ProductRepositoryFactory productRepoFactory,
      UserRepositoryFactory userRepoFactory) {
    this.rootDsl = rootDsl;
    this.receiptRepoFactory = receiptRepoFactory;
    this.supplierRepoFactory = supplierRepoFactory;
    this.inventoryRepoFactory = inventoryRepoFactory;
    this.inventoryLogRepoFactory = inventoryLogRepoFactory;
    this.productRepoFactory = productRepoFactory;
    this.userRepoFactory = userRepoFactory;
  }

  // Wire shapes

  /** One delivery-note line as the client keys it. */
  public record LineCommand(UUID productId, int quantity, BigDecimal unitCost) {}

  /** The delivery note. {@code receivedAt} null defaults to now. */
  public record ReceiptCommand(
      UUID supplierId,
      OffsetDateTime receivedAt,
      String supplierReference,
      String notes,
      List<LineCommand> lines) {}

  /**
   * A receipt with everything a client renders it from: the supplier, each line's product, each
   * line's {@code stock_after} (read from the movements, never stored twice), and who voided it.
   */
  public record ReceiptView(
      GoodsReceipt receipt,
      Supplier supplier,
      Map<UUID, Product> products,
      Map<UUID, Integer> stockAfter,
      String voidedByName,
      boolean replayed) {}

  /** One page of receipts + their suppliers + line counts + the total. */
  public record ReceiptPage(
      List<GoodsReceipt> receipts,
      Map<UUID, Supplier> suppliers,
      Map<UUID, Integer> lineCounts,
      long total) {}

  // Write

  /**
   * Post a delivery: stock, cost and the movement ledger all move off one document, in one
   * transaction. Replay of {@code idempotencyKey} returns the prior receipt and re-applies nothing;
   * the same key with a different body is a 409.
   */
  public ReceiptView record(
      UUID orgId, ReceiptCommand cmd, String idempotencyKey, ActorContext actor, UUID actorUserId) {
    String key = requireKey(idempotencyKey);
    List<LineCommand> lines = validateLines(cmd.lines());
    OffsetDateTime receivedAt = validateReceivedAt(cmd.receivedAt());
    String supplierRef = validateRef(cmd.supplierReference());
    if (cmd.supplierId() == null) {
      throw new ValidationException("supplier_id is required");
    }

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          GoodsReceiptRepository receipts = receiptRepoFactory.create(tx);

          var prior = receipts.findByIdempotencyKey(orgId, key);
          if (prior.isPresent()) {
            GoodsReceipt existing = prior.get();
            existing.setLines(receipts.findLines(existing.getId()));
            requireSameRequest(existing, cmd.supplierId(), lines, key);
            log.info("Goods receipt replay orgId={} key={} id={}", orgId, key, existing.getId());
            return view(tx, orgId, existing, true);
          }

          Supplier supplier =
              supplierRepoFactory
                  .create(tx)
                  .findById(orgId, cmd.supplierId())
                  .orElseThrow(() -> new NotFoundException("Supplier", cmd.supplierId()));
          if (!supplier.isActive()) {
            throw new ConflictException(
                "Supplier '"
                    + supplier.getName()
                    + "' is retired — reactivate it to receive from it");
          }

          Map<UUID, Product> products = productsInOrg(tx, orgId, lines);

          GoodsReceipt receipt = new GoodsReceipt();
          receipt.setOrgId(orgId);
          receipt.setSupplierId(supplier.getId());
          receipt.setReceiptNumber(
              receipts.claimReceiptNumber(
                  orgId, receivedAt.atZoneSameInstant(ZoneOffset.UTC).getYear()));
          receipt.setStatus(GoodsReceiptStatus.POSTED);
          receipt.setReceivedAt(receivedAt);
          receipt.setSupplierReference(supplierRef);
          receipt.setNotes(trimToNull(cmd.notes()));
          receipt.setIdempotencyKey(key);
          receipt.setCreatedBy(actorUserId);
          receipt.setLines(lines.stream().map(GoodsReceiptService::toLine).toList());
          receipt.setTotalCost(
              receipt.getLines().stream()
                  .map(GoodsReceiptLine::getLineTotal)
                  .reduce(BigDecimal.ZERO, BigDecimal::add)
                  .setScale(MONEY_SCALE, RoundingMode.UNNECESSARY));

          GoodsReceipt saved = receipts.insert(receipt);
          applyStock(tx, orgId, saved, +1, actor);

          log.info(
              "Recorded goods receipt id={} number={} orgId={} supplierId={} total={}",
              saved.getId(),
              saved.getReceiptNumber(),
              orgId,
              supplier.getId(),
              saved.getTotalCost());
          return view(tx, orgId, saved, false);
        });
  }

  /**
   * Undo a receipt keyed wrong: one negative movement per line at the <b>same</b> cost and the same
   * {@code goods_receipt_id}, so the ledger reverses with it.
   *
   * <p>Deliberately not an {@code ADJUSTMENT} — that posts to 5100 shrinkage, booking a keying
   * error as a P&L loss and leaving 2000 overstated forever. And deliberately <b>not</b> a restore
   * of {@code product.cost_price}: nothing knows what the cost was before, and inventing it is
   * worse than leaving the figure the merchant can see and change.
   */
  public ReceiptView voidReceipt(
      UUID orgId, UUID id, String reason, ActorContext actor, UUID actorUserId) {
    String voidReason = requireVoidReason(reason);
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          GoodsReceiptRepository receipts = receiptRepoFactory.create(tx);
          GoodsReceipt receipt =
              receipts
                  .lockById(orgId, id)
                  .orElseThrow(() -> new NotFoundException("GoodsReceipt", id));
          if (receipt.getStatus() == GoodsReceiptStatus.VOIDED) {
            throw new ConflictException(
                "Goods receipt " + receipt.getReceiptNumber() + " is already voided");
          }
          receipt.setLines(receipts.findLines(receipt.getId()));

          applyStock(tx, orgId, receipt, -1, actor);
          GoodsReceipt voided =
              receipts.markVoided(orgId, id, voidReason, actorUserId, OffsetDateTime.now());

          log.info(
              "Voided goods receipt id={} number={} orgId={}",
              id,
              voided.getReceiptNumber(),
              orgId);
          return view(tx, orgId, voided, false);
        });
  }

  /**
   * The stock half of both verbs, {@code sign = +1} to post and {@code −1} to reverse: lock, guard,
   * move, log. One definition, so a void can never move stock by a different rule than the receipt.
   */
  private void applyStock(
      DSLContext tx, UUID orgId, GoodsReceipt receipt, int sign, ActorContext actor) {
    InventoryRepository inventory = inventoryRepoFactory.create(tx);
    InventoryLogRepository logs = inventoryLogRepoFactory.create(tx);
    ProductRepository products = productRepoFactory.create(tx);

    List<UUID> productIds =
        receipt.getLines().stream().map(GoodsReceiptLine::getProductId).toList();
    Map<UUID, Inventory> locked = inventory.lockForUpdate(orgId, productIds);

    for (GoodsReceiptLine line : receipt.getLines()) {
      Inventory current = locked.get(line.getProductId());
      if (current == null) {
        throw untracked(tx, orgId, line.getProductId());
      }
      int delta = sign * line.getQuantity();
      int stockAfter = current.getStockQty() + delta;
      if (stockAfter < current.getReservedQty()) {
        throw new ConflictException(
            "Stock cannot go below the reserved quantity: "
                + current.getReservedQty()
                + " units of "
                + productLabel(products, orgId, line.getProductId())
                + " are held by open orders (stock would be "
                + stockAfter
                + "). Sell-through or a stocktake adjustment is the remedy, not a void.");
      }

      Inventory updated =
          inventory.adjustQuantities(orgId, line.getProductId(), delta, 0, current.getVersion());
      logs.insert(
          orgId,
          line.getProductId(),
          delta,
          0,
          updated.getStockQty(),
          updated.getReservedQty(),
          StockReason.RESTOCK,
          null,
          actor,
          line.getUnitCost(),
          receipt.getId());

      // The cost write-back is the point of the slice, not a side effect: cost_price is what the
      // NEXT sale freezes. A void never restores the old figure (see the method Javadoc).
      if (sign > 0) {
        products.updateCostPrice(orgId, line.getProductId(), line.getUnitCost());
      }
    }
  }

  // Reads

  public ReceiptView getById(UUID orgId, UUID id) {
    GoodsReceiptRepository receipts = receiptRepoFactory.create(rootDsl);
    GoodsReceipt receipt =
        receipts.findById(orgId, id).orElseThrow(() -> new NotFoundException("GoodsReceipt", id));
    receipt.setLines(receipts.findLines(id));
    return view(rootDsl, orgId, receipt, false);
  }

  public ReceiptPage list(UUID orgId, GoodsReceiptListFilter filter, int page, int size) {
    int p = requirePage(page);
    int s = requireSize(size);
    GoodsReceiptRepository receipts = receiptRepoFactory.create(rootDsl);
    List<GoodsReceipt> rows = receipts.list(orgId, filter, p * s, s);
    return new ReceiptPage(
        rows,
        supplierRepoFactory
            .create(rootDsl)
            .findByIds(orgId, rows.stream().map(GoodsReceipt::getSupplierId).distinct().toList()),
        receipts.lineCounts(rows.stream().map(GoodsReceipt::getId).toList()),
        receipts.count(orgId, filter));
  }

  /** Suppliers for a page of receipts — the subresource read needs the same decoration. */
  public Map<UUID, Supplier> suppliersFor(UUID orgId, List<GoodsReceipt> receipts) {
    return supplierRepoFactory
        .create(rootDsl)
        .findByIds(orgId, receipts.stream().map(GoodsReceipt::getSupplierId).distinct().toList());
  }

  private ReceiptView view(DSLContext ctx, UUID orgId, GoodsReceipt receipt, boolean replayed) {
    Supplier supplier =
        supplierRepoFactory.create(ctx).findById(orgId, receipt.getSupplierId()).orElse(null);
    Map<UUID, Product> products =
        productRepoFactory
            .create(ctx)
            .findByIds(
                orgId, receipt.getLines().stream().map(GoodsReceiptLine::getProductId).toList())
            .stream()
            .collect(HashMap::new, (m, p) -> m.put(p.getId(), p), HashMap::putAll);
    String voidedByName =
        receipt.getVoidedBy() == null
            ? null
            : userRepoFactory
                .create(ctx)
                .findById(receipt.getVoidedBy())
                .map(GoodsReceiptService::displayNameOf)
                .orElse(null);
    return new ReceiptView(
        receipt, supplier, products, stockAfter(ctx, orgId, receipt), voidedByName, replayed);
  }

  /**
   * Each line's balance right after this receipt moved it, read back from the movements it wrote:
   * per product the FIRST row this receipt caused (the void's reversal comes after it by id).
   */
  private Map<UUID, Integer> stockAfter(DSLContext ctx, UUID orgId, GoodsReceipt receipt) {
    Map<UUID, Integer> out = new LinkedHashMap<>();
    for (InventoryLog l :
        inventoryLogRepoFactory.create(ctx).findByGoodsReceiptId(orgId, receipt.getId())) {
      out.putIfAbsent(l.getProductId(), l.getStockAfter());
    }
    return out;
  }

  // Validation

  private Map<UUID, Product> productsInOrg(DSLContext tx, UUID orgId, List<LineCommand> lines) {
    List<UUID> ids = lines.stream().map(LineCommand::productId).toList();
    Map<UUID, Product> byId = new HashMap<>();
    productRepoFactory.create(tx).findByIds(orgId, ids).forEach(p -> byId.put(p.getId(), p));
    for (UUID id : ids) {
      if (!byId.containsKey(id)) {
        throw new NotFoundException("Product", id);
      }
    }
    return byId;
  }

  /**
   * An untracked product is a 409 naming it with nothing written — the {@code counter_return.md}
   * rule. Auto-initialising would let a typo in a product picker create inventory rows.
   */
  private ConflictException untracked(DSLContext tx, UUID orgId, UUID productId) {
    return new ConflictException(
        productLabel(productRepoFactory.create(tx), orgId, productId)
            + " has no inventory record — initialise its stock first");
  }

  private static String productLabel(ProductRepository products, UUID orgId, UUID productId) {
    return products
        .findById(orgId, productId)
        .map(p -> p.getName() + " (" + p.getSku() + ")")
        .orElse("Product " + productId);
  }

  private static List<LineCommand> validateLines(List<LineCommand> raw) {
    if (raw == null || raw.isEmpty()) {
      throw new ValidationException("lines are required");
    }
    if (raw.size() > MAX_LINES) {
      throw new ValidationException("a receipt may carry at most " + MAX_LINES + " lines");
    }
    Set<UUID> seen = new HashSet<>();
    List<LineCommand> out = new ArrayList<>(raw.size());
    for (LineCommand l : raw) {
      if (l.productId() == null) {
        throw new ValidationException("each line needs a product_id");
      }
      if (!seen.add(l.productId())) {
        // Two lines of one product at two costs is a cost question this slice does not answer.
        throw new ValidationException("duplicate product in lines: " + l.productId());
      }
      if (l.quantity() <= 0) {
        throw new ValidationException("quantity must be > 0");
      }
      BigDecimal cost = l.unitCost();
      if (cost == null || cost.signum() < 0) {
        throw new ValidationException("unit_cost must be >= 0");
      }
      if (cost.scale() > MONEY_SCALE) {
        throw new ValidationException("unit_cost must have at most 2 decimal places");
      }
      out.add(new LineCommand(l.productId(), l.quantity(), cost.setScale(MONEY_SCALE)));
    }
    return out;
  }

  private static GoodsReceiptLine toLine(LineCommand c) {
    return new GoodsReceiptLine(c.productId(), c.quantity(), c.unitCost());
  }

  private static OffsetDateTime validateReceivedAt(OffsetDateTime receivedAt) {
    OffsetDateTime now = OffsetDateTime.now();
    if (receivedAt == null) {
      return now;
    }
    if (receivedAt.isAfter(now)) {
      throw new ValidationException("received_at cannot be in the future");
    }
    return receivedAt;
  }

  private static String validateRef(String ref) {
    String r = trimToNull(ref);
    if (r != null && r.length() > SUPPLIER_REFERENCE_MAX) {
      throw new ValidationException(
          "supplier_reference must be at most " + SUPPLIER_REFERENCE_MAX + " characters");
    }
    return r;
  }

  private static String requireKey(String key) {
    if (key == null || key.isBlank()) {
      throw new ValidationException("Idempotency-Key header is required");
    }
    return key.trim();
  }

  private static String requireVoidReason(String reason) {
    String r = trimToNull(reason);
    if (r == null) {
      throw new ValidationException("reason is required");
    }
    if (r.length() > VOID_REASON_MAX) {
      throw new ValidationException("reason must be at most " + VOID_REASON_MAX + " characters");
    }
    return r;
  }

  /** A key reused with a different delivery is a client bug, surfaced rather than swallowed. */
  private static void requireSameRequest(
      GoodsReceipt prior, UUID supplierId, List<LineCommand> lines, String key) {
    boolean same =
        Objects.equals(prior.getSupplierId(), supplierId)
            && prior.getLines().size() == lines.size()
            && lines.stream()
                .allMatch(
                    l ->
                        prior.getLines().stream()
                            .anyMatch(
                                p ->
                                    p.getProductId().equals(l.productId())
                                        && p.getQuantity() == l.quantity()
                                        && p.getUnitCost().compareTo(l.unitCost()) == 0));
    if (!same) {
      throw new ConflictException("Idempotency-Key reused with different parameters: " + key);
    }
  }

  private static String displayNameOf(AppUser u) {
    return u.getDisplayName() != null && !u.getDisplayName().isBlank()
        ? u.getDisplayName()
        : u.getEmail();
  }

  private static String trimToNull(String raw) {
    if (raw == null) return null;
    String t = raw.trim();
    return t.isEmpty() ? null : t;
  }

  private static int requirePage(int page) {
    if (page < 0) throw new ValidationException("page must be >= 0");
    return page;
  }

  private static int requireSize(int size) {
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ValidationException("size must be 1-" + MAX_PAGE_SIZE);
    }
    return size;
  }
}
