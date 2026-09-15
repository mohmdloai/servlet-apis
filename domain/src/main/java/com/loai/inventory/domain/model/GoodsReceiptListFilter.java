package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code GET /goods-receipts} narrowing: supplier ∧ status ∧ [from, to) on {@code received_at} ∧ q,
 * where {@code q} is a case-insensitive fragment of our number or theirs.
 *
 * <p>An unknown {@code supplierId} here is an <b>empty page</b> — a query parameter narrows a set,
 * the opposite of the {@code /suppliers/{id}/receipts} subresource's 404, and both are right.
 */
public record GoodsReceiptListFilter(
    UUID supplierId, GoodsReceiptStatus status, OffsetDateTime from, OffsetDateTime to, String q) {

  public static GoodsReceiptListFilter none() {
    return new GoodsReceiptListFilter(null, null, null, null, null);
  }

  public static GoodsReceiptListFilter ofSupplier(UUID supplierId) {
    return new GoodsReceiptListFilter(supplierId, null, null, null, null);
  }
}
