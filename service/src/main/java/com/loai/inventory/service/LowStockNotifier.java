package com.loai.inventory.service;

import com.loai.inventory.domain.model.NotificationType;
import com.loai.inventory.domain.model.Product;
import com.loai.inventory.domain.repository.ProductRepository;
import com.loai.inventory.domain.repository.ProductRepositoryFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The reorder-point check a sale runs after it has moved stock ({@code stories/reorder_point.md},
 * V94). Not a mechanism of its own: it reads the product's {@code reorder_point} and calls the one
 * existing staff fan-out, {@link NotificationService#notifyOrgStaff}, inside the caller's
 * transaction — so a sale that rolls back (a shortage, a denied approval) tells nobody.
 *
 * <p>Fires on the <b>crossing</b> — {@code availableBefore > reorderPoint && availableAfter <=
 * reorderPoint} — never on the state. A product already at or below its point sells on in silence;
 * a restock (or a release) that lifts it above the point re-arms it, and the next descent fires
 * again. There is deliberately no "already notified" flag anywhere: the feed is the record.
 *
 * <p>Two callers, the two places a sale reduces what can be sold: the reservation at online/phone
 * placement ({@code reserved +q}) and the in-store sale ({@code stock −q}). A ship moves stock and
 * reserved together and leaves available where it was, so it is not a caller.
 */
public class LowStockNotifier {
  private static final Logger log = LoggerFactory.getLogger(LowStockNotifier.class);

  /** One product's available quantity before and after the sale's write. */
  public record StockMove(UUID productId, int availableBefore, int availableAfter) {}

  private final ProductRepositoryFactory productRepoFactory;
  private final NotificationService notificationService;

  public LowStockNotifier(
      ProductRepositoryFactory productRepoFactory, NotificationService notificationService) {
    this.productRepoFactory = productRepoFactory;
    this.notificationService = notificationService;
  }

  /**
   * Raise {@code LOW_STOCK} to the org's staff for every move that crosses its product's reorder
   * point. Products are batch-loaded once ({@code findByIds}); a product with no point, or one that
   * was already at or below it, is skipped. Runs in {@code txDsl}.
   */
  public void afterSale(DSLContext txDsl, UUID orgId, List<StockMove> moves) {
    if (moves == null || moves.isEmpty()) {
      return;
    }
    // Only descents can cross downward; skip the read entirely when nothing went down.
    List<StockMove> descents = new ArrayList<>();
    for (StockMove m : moves) {
      if (m.availableAfter() < m.availableBefore()) {
        descents.add(m);
      }
    }
    if (descents.isEmpty()) {
      return;
    }
    ProductRepository products = productRepoFactory.create(txDsl);
    Map<UUID, Product> byId = new LinkedHashMap<>();
    for (Product p :
        products.findByIds(
            orgId, descents.stream().map(StockMove::productId).distinct().toList())) {
      byId.put(p.getId(), p);
    }
    for (StockMove m : descents) {
      Product p = byId.get(m.productId());
      if (p == null || p.getReorderPoint() == null) {
        continue;
      }
      int point = p.getReorderPoint();
      if (m.availableBefore() > point && m.availableAfter() <= point) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("product_id", p.getId().toString());
        payload.put("name", p.getName());
        payload.put("sku", p.getSku());
        payload.put("available", m.availableAfter());
        payload.put("reorder_point", point);
        notificationService.notifyOrgStaff(
            txDsl,
            orgId,
            NotificationType.LOW_STOCK,
            payload,
            "product",
            p.getId(),
            "/orgs/" + orgId + "/inventory/" + p.getId());
        log.info(
            "LOW_STOCK orgId={} productId={} available {}→{} reorderPoint={}",
            orgId,
            p.getId(),
            m.availableBefore(),
            m.availableAfter(),
            point);
      }
    }
  }
}
