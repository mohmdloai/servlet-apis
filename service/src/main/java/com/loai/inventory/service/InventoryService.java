package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Inventory;
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

  public InventoryService(DSLContext rootDsl, InventoryRepositoryFactory repoFactory) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
  }

  // ── Queries

  public Inventory getByProductId(UUID productId) {
    InventoryRepository repo = repoFactory.create(rootDsl);
    return repo.findByProductId(productId)
        .orElseThrow(() -> new NotFoundException("Inventory", productId));
  }

  // ── Commands

  public Inventory initialise(UUID productId, int stockQty) {
    if (stockQty < 0) throw new ValidationException("stockQty must be >= 0");

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);

          if (repo.existsByProductId(productId)) {
            throw new ConflictException("Inventory already exists for product: " + productId);
          }

          Inventory inventory = new Inventory();
          inventory.setProductId(productId);
          inventory.setStockQty(stockQty);

          Inventory saved = repo.insert(inventory);
          log.info("Initialised inventory productId={} stock={}", productId, stockQty);
          return saved;
        });
  }

  public Inventory reserve(UUID productId, int qty) {
    validateQty(qty, "reserve");

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);

          Inventory current = findOrThrow(repo, productId);

          if (current.getAvailableQty() < qty) {
            throw new ValidationException(
                "Insufficient available stock: available="
                    + current.getAvailableQty()
                    + ", requested="
                    + qty);
          }

          return repo.adjustQuantities(productId, 0, qty, current.getVersion());
        });
  }

  public Inventory release(UUID productId, int qty) {
    validateQty(qty, "release");

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);

          Inventory current = findOrThrow(repo, productId);

          if (current.getReservedQty() < qty) {
            throw new ValidationException(
                "Cannot release more than reserved: reserved="
                    + current.getReservedQty()
                    + ", requested="
                    + qty);
          }

          return repo.adjustQuantities(productId, 0, -qty, current.getVersion());
        });
  }

  public Inventory confirmSale(UUID productId, int qty) {
    validateQty(qty, "confirmSale");

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);

          Inventory current = findOrThrow(repo, productId);

          if (current.getReservedQty() < qty) {
            throw new ValidationException(
                "Cannot sell more than reserved: reserved="
                    + current.getReservedQty()
                    + ", requested="
                    + qty);
          }

          return repo.adjustQuantities(productId, -qty, -qty, current.getVersion());
        });
  }

  public Inventory restock(UUID productId, int qty) {
    validateQty(qty, "restock");

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);

          Inventory current = findOrThrow(repo, productId);
          return repo.adjustQuantities(productId, qty, 0, current.getVersion());
        });
  }

  public Inventory adjust(UUID productId, int stockDelta) {
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);

          Inventory current = findOrThrow(repo, productId);
          return repo.adjustQuantities(productId, stockDelta, 0, current.getVersion());
        });
  }

  public void delete(UUID productId) {
    rootDsl.transaction(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          InventoryRepository repo = repoFactory.create(txDsl);

          repo.deleteByProductId(productId);
          log.info("Deleted inventory productId={}", productId);
        });
  }

  // ── Helpers

  private Inventory findOrThrow(InventoryRepository repo, UUID productId) {
    return repo.findByProductId(productId)
        .orElseThrow(() -> new NotFoundException("Inventory", productId));
  }

  private void validateQty(int qty, String operation) {
    if (qty <= 0) {
      throw new ValidationException(operation + " qty must be > 0");
    }
  }
}
