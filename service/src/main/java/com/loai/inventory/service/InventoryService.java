package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.domain.model.StockReason;
import com.loai.inventory.domain.repository.InventoryLogRepository;
import com.loai.inventory.domain.repository.InventoryLogRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryRepository;
import com.loai.inventory.domain.repository.InventoryRepositoryFactory;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InventoryService {
  private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

  private final DSLContext rootDsl;
  private final InventoryRepositoryFactory repoFactory;
  private final InventoryLogRepositoryFactory logRepoFactory;

  public InventoryService(
      DSLContext rootDsl,
      InventoryRepositoryFactory repoFactory,
      InventoryLogRepositoryFactory logRepoFactory) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
    this.logRepoFactory = logRepoFactory;
  }

  public Inventory getByProductId(UUID orgId, UUID productId) {
    InventoryRepository repo = repoFactory.create(rootDsl);
    return repo.findByProductId(orgId, productId)
        .orElseThrow(() -> new NotFoundException("Inventory", productId));
  }

  public Inventory initialise(UUID orgId, UUID productId, int stockQty, ActorContext actor) {
    if (stockQty < 0) throw new ValidationException("stockQty must be >= 0");

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);
          InventoryLogRepository logRepo = logRepoFactory.create(txDsl);

          if (repo.existsByProductId(orgId, productId)) {
            throw new ConflictException("Inventory already exists for product: " + productId);
          }

          Inventory inventory = new Inventory();
          inventory.setOrgId(orgId);
          inventory.setProductId(productId);
          inventory.setStockQty(stockQty);

          Inventory saved = repo.insert(inventory);

          logRepo.insert(
              orgId,
              productId,
              stockQty,
              0,
              saved.getStockQty(),
              saved.getReservedQty(),
              StockReason.RESTOCK,
              null,
              actor);

          log.info(
              "Initialised inventory orgId={} productId={} stock={}", orgId, productId, stockQty);
          return saved;
        });
  }

  public Inventory reserve(UUID orgId, UUID productId, int qty, ActorContext actor) {
    return reserve(orgId, productId, qty, null, actor);
  }

  public Inventory reserve(UUID orgId, UUID productId, int qty, UUID orderId, ActorContext actor) {
    validateQty(qty, "reserve");
    validateOrderId(StockReason.RESERVED, orderId);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);
          InventoryLogRepository logRepo = logRepoFactory.create(txDsl);

          Inventory current = findOrThrow(repo, orgId, productId);

          if (current.getAvailableQty() < qty) {
            throw new ValidationException(
                "Insufficient available stock: available="
                    + current.getAvailableQty()
                    + ", requested="
                    + qty);
          }

          Inventory updated = repo.adjustQuantities(orgId, productId, 0, qty, current.getVersion());

          logRepo.insert(
              orgId,
              productId,
              0,
              qty,
              updated.getStockQty(),
              updated.getReservedQty(),
              StockReason.RESERVED,
              orderId,
              actor);

          return updated;
        });
  }

  public Inventory release(UUID orgId, UUID productId, int qty, ActorContext actor) {
    return release(orgId, productId, qty, null, actor);
  }

  public Inventory release(UUID orgId, UUID productId, int qty, UUID orderId, ActorContext actor) {
    validateQty(qty, "release");
    validateOrderId(StockReason.RELEASED, orderId);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);
          InventoryLogRepository logRepo = logRepoFactory.create(txDsl);

          Inventory current = findOrThrow(repo, orgId, productId);

          if (current.getReservedQty() < qty) {
            throw new ValidationException(
                "Cannot release more than reserved: reserved="
                    + current.getReservedQty()
                    + ", requested="
                    + qty);
          }

          Inventory updated =
              repo.adjustQuantities(orgId, productId, 0, -qty, current.getVersion());

          logRepo.insert(
              orgId,
              productId,
              0,
              -qty,
              updated.getStockQty(),
              updated.getReservedQty(),
              StockReason.RELEASED,
              orderId,
              actor);

          return updated;
        });
  }

  public Inventory confirmSale(UUID orgId, UUID productId, int qty, ActorContext actor) {
    return confirmSale(orgId, productId, qty, null, actor);
  }

  public Inventory confirmSale(
      UUID orgId, UUID productId, int qty, UUID orderId, ActorContext actor) {
    validateQty(qty, "confirmSale");
    validateOrderId(StockReason.SOLD, orderId);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);
          InventoryLogRepository logRepo = logRepoFactory.create(txDsl);

          Inventory current = findOrThrow(repo, orgId, productId);

          if (current.getReservedQty() < qty) {
            throw new ValidationException(
                "Cannot sell more than reserved: reserved="
                    + current.getReservedQty()
                    + ", requested="
                    + qty);
          }

          Inventory updated =
              repo.adjustQuantities(orgId, productId, -qty, -qty, current.getVersion());

          logRepo.insert(
              orgId,
              productId,
              -qty,
              -qty,
              updated.getStockQty(),
              updated.getReservedQty(),
              StockReason.SOLD,
              orderId,
              actor);

          return updated;
        });
  }

  public Inventory restock(UUID orgId, UUID productId, int qty, ActorContext actor) {
    validateQty(qty, "restock");

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);
          InventoryLogRepository logRepo = logRepoFactory.create(txDsl);

          Inventory current = findOrThrow(repo, orgId, productId);
          Inventory updated = repo.adjustQuantities(orgId, productId, qty, 0, current.getVersion());

          logRepo.insert(
              orgId,
              productId,
              qty,
              0,
              updated.getStockQty(),
              updated.getReservedQty(),
              StockReason.RESTOCK,
              null,
              actor);

          return updated;
        });
  }

  public Inventory adjust(UUID orgId, UUID productId, int stockDelta, ActorContext actor) {
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);
          InventoryLogRepository logRepo = logRepoFactory.create(txDsl);

          Inventory current = findOrThrow(repo, orgId, productId);
          Inventory updated =
              repo.adjustQuantities(orgId, productId, stockDelta, 0, current.getVersion());

          logRepo.insert(
              orgId,
              productId,
              stockDelta,
              0,
              updated.getStockQty(),
              updated.getReservedQty(),
              StockReason.ADJUSTMENT,
              null,
              actor);

          return updated;
        });
  }

  public void delete(UUID orgId, UUID productId, ActorContext actor) {
    rootDsl.transaction(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);

          repo.deleteByProductId(orgId, productId);
          log.info("Deleted inventory orgId={} productId={}", orgId, productId);
        });
  }

  private Inventory findOrThrow(InventoryRepository repo, UUID orgId, UUID productId) {
    return repo.findByProductId(orgId, productId)
        .orElseThrow(() -> new NotFoundException("Inventory", productId));
  }

  private void validateQty(int qty, String operation) {
    if (qty <= 0) {
      throw new ValidationException(operation + " qty must be > 0");
    }
  }

  private void validateOrderId(StockReason reason, UUID orderId) {
    if (reason.requiresOrderId() && orderId == null) {
      throw new ValidationException(reason.getDisplayName() + " requires an orderId");
    }
  }
}
