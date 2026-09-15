package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.GoodsReceipt;
import com.loai.inventory.domain.model.GoodsReceiptLine;
import com.loai.inventory.domain.model.GoodsReceiptListFilter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface GoodsReceiptRepository {

  /** The header alone; {@link #findLines} carries the lines. */
  Optional<GoodsReceipt> findById(UUID orgId, UUID id);

  /** The header under {@code FOR UPDATE} — the void's serialisation point. */
  Optional<GoodsReceipt> lockById(UUID orgId, UUID id);

  Optional<GoodsReceipt> findByIdempotencyKey(UUID orgId, String idempotencyKey);

  List<GoodsReceiptLine> findLines(UUID goodsReceiptId);

  /**
   * One page of receipts, {@code received_at DESC, id DESC} <b>always</b>, filtered or not: neither
   * POSTED nor VOIDED is a worklist — a receipt is done the moment it is posted and a voided one is
   * history, so the queue-vs-ledger convention has nothing to switch on here.
   */
  List<GoodsReceipt> list(UUID orgId, GoodsReceiptListFilter filter, int offset, int limit);

  long count(UUID orgId, GoodsReceiptListFilter filter);

  /** Line counts per receipt id — one query for a page of lean rows. */
  Map<UUID, Integer> lineCounts(Collection<UUID> receiptIds);

  /** {@code GRN-YYYY-NNNNN}, allocated FOR UPDATE in the caller's txn: a rollback burns nothing. */
  String claimReceiptNumber(UUID orgId, int year);

  /** Header + lines, one document. */
  GoodsReceipt insert(GoodsReceipt receipt);

  /** POSTED → VOIDED with its author and reason; the figures stay, because it is history. */
  GoodsReceipt markVoided(
      UUID orgId, UUID id, String reason, UUID voidedBy, java.time.OffsetDateTime voidedAt);

  /** Whether any receipt in the org references {@code supplierId} (the delete guard). */
  boolean existsForSupplier(UUID orgId, UUID supplierId);

  /**
   * Receipt numbers by id, batch-loaded onto movement-ledger rows — sales_order_number's mirror.
   */
  Map<UUID, String> findReceiptNumbersByIds(UUID orgId, Collection<UUID> ids);
}
