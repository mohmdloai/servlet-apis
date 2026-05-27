package com.loai.inventory.common.exception;

import java.util.List;
import java.util.UUID;

/**
 * 409 Conflict raised when one or more products on a placement request have less available stock
 * than requested. Carries a per-product shortage list so the caller can show all problems at once
 * rather than fixing them one at a time.
 */
public class InsufficientStockException extends ConflictException {

  private final List<Shortage> shortages;

  public InsufficientStockException(List<Shortage> shortages) {
    super(buildMessage(shortages));
    this.shortages = List.copyOf(shortages);
  }

  public List<Shortage> getShortages() {
    return shortages;
  }

  private static String buildMessage(List<Shortage> shortages) {
    return "Insufficient stock for " + shortages.size() + " product(s)";
  }

  public record Shortage(UUID productId, int requested, int available) {}
}
