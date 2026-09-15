package com.loai.inventory.domain.model.ledger;

import java.util.Map;

/**
 * What one poster run inserted, per event kind ({@code "INVOICE/ISSUED" → n}). Zero everywhere on a
 * ledger that was already current — the ordinary outcome of a catch-up on read.
 */
public record PostingSummary(Map<String, Integer> inserted) {

  public int total() {
    return inserted.values().stream().mapToInt(Integer::intValue).sum();
  }
}
