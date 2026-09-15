package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.GoodsReceipt;
import com.loai.inventory.domain.model.GoodsReceiptListFilter;
import com.loai.inventory.domain.model.Supplier;
import com.loai.inventory.domain.repository.GoodsReceiptRepository;
import com.loai.inventory.domain.repository.GoodsReceiptRepositoryFactory;
import com.loai.inventory.domain.repository.SupplierRepository;
import com.loai.inventory.domain.repository.SupplierRepositoryFactory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The supplier directory ({@code stories/supplier_goods_receipt.md}) — the customer CRM record's
 * mirror: org-scoped, no authentication, nothing supplier-facing. It carries a name, a phone and an
 * address and <b>no money at all</b>, which is why it keeps the {@code /customers} gates
 * (VIEWER/STAFF/MANAGER) while every {@code /goods-receipts} route is MANAGER.
 */
public class SupplierService {
  private static final Logger log = LoggerFactory.getLogger(SupplierService.class);

  public static final int NAME_MAX = 200;
  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  private final DSLContext rootDsl;
  private final SupplierRepositoryFactory repoFactory;
  private final GoodsReceiptRepositoryFactory receiptRepoFactory;

  public SupplierService(
      DSLContext rootDsl,
      SupplierRepositoryFactory repoFactory,
      GoodsReceiptRepositoryFactory receiptRepoFactory) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
    this.receiptRepoFactory = receiptRepoFactory;
  }

  /** What a write may set; a null field on an update is leave-unchanged (the merge-PUT rule). */
  public record SupplierEdit(
      String name, String phone, String email, String address, String notes, Boolean active) {}

  /** One page of the directory + its total, from one predicate. */
  public record SupplierPage(List<Supplier> suppliers, long total) {}

  /** One page of a supplier's receipts + their line counts + the total. */
  public record SupplierReceipts(
      List<GoodsReceipt> receipts, Map<UUID, Integer> lineCounts, long total) {}

  public Supplier getById(UUID orgId, UUID id) {
    return repoFactory
        .create(rootDsl)
        .findById(orgId, id)
        .orElseThrow(() -> new NotFoundException("Supplier", id));
  }

  public SupplierPage list(UUID orgId, String q, Boolean active, int page, int size) {
    int p = requirePage(page);
    int s = requireSize(size);
    String term = (q == null || q.isBlank()) ? null : q.trim();
    SupplierRepository repo = repoFactory.create(rootDsl);
    return new SupplierPage(
        repo.findAll(orgId, term, active, p * s, s), repo.count(orgId, term, active));
  }

  /**
   * {@code GET /suppliers/{id}/receipts} — the {@code customers/{id}/orders} precedent: a
   * subresource, not a {@code ?supplier_id=} on a route that already answers two shapes. Unknown
   * supplier → 404 (a path segment names a thing); a supplier with no receipts → an empty page.
   */
  public SupplierReceipts receipts(UUID orgId, UUID supplierId, int page, int size) {
    int p = requirePage(page);
    int s = requireSize(size);
    getById(orgId, supplierId);
    GoodsReceiptRepository repo = receiptRepoFactory.create(rootDsl);
    GoodsReceiptListFilter filter = GoodsReceiptListFilter.ofSupplier(supplierId);
    List<GoodsReceipt> rows = repo.list(orgId, filter, p * s, s);
    return new SupplierReceipts(
        rows,
        repo.lineCounts(rows.stream().map(GoodsReceipt::getId).toList()),
        repo.count(orgId, filter));
  }

  public Supplier create(UUID orgId, SupplierEdit edit) {
    String name = requireName(edit.name());
    return rootDsl.transactionResult(
        cfg -> {
          SupplierRepository repo = repoFactory.create(DSL.using(cfg));
          requireFoldedNameFree(repo, orgId, name, null);

          Supplier s = new Supplier();
          s.setOrgId(orgId);
          s.setName(name);
          s.setPhone(trimToNull(edit.phone()));
          s.setEmail(Text.normalizeEmail(edit.email()));
          s.setAddress(trimToNull(edit.address()));
          s.setNotes(trimToNull(edit.notes()));
          s.setActive(edit.active() == null || edit.active());

          Supplier saved = repo.insert(s);
          log.info("Created supplier id={} orgId={}", saved.getId(), orgId);
          return saved;
        });
  }

  /** Merge update: a null field is leave-unchanged (the {@code PUT /api/orgs/{orgId}} rule). */
  public Supplier update(UUID orgId, UUID id, SupplierEdit edit) {
    return rootDsl.transactionResult(
        cfg -> {
          SupplierRepository repo = repoFactory.create(DSL.using(cfg));
          Supplier existing =
              repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Supplier", id));

          if (edit.name() != null) {
            String name = requireName(edit.name());
            requireFoldedNameFree(repo, orgId, name, id);
            existing.setName(name);
          }
          if (edit.phone() != null) existing.setPhone(trimToNull(edit.phone()));
          if (edit.email() != null) existing.setEmail(Text.normalizeEmail(edit.email()));
          if (edit.address() != null) existing.setAddress(trimToNull(edit.address()));
          if (edit.notes() != null) existing.setNotes(trimToNull(edit.notes()));
          if (edit.active() != null) existing.setActive(edit.active());

          Supplier updated = repo.update(existing);
          log.info("Updated supplier id={} orgId={}", id, orgId);
          return updated;
        });
  }

  /**
   * Delete, or a 409 naming {@code active:false} when receipts reference the supplier — the {@code
   * DELETE /products} precedent. Checked here rather than caught as an FK violation so the message
   * can name the remedy the flag exists for.
   */
  public void delete(UUID orgId, UUID id) {
    rootDsl.transaction(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          SupplierRepository repo = repoFactory.create(tx);
          Supplier existing =
              repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Supplier", id));
          if (receiptRepoFactory.create(tx).existsForSupplier(orgId, id)) {
            throw new ConflictException(
                "Supplier '"
                    + existing.getName()
                    + "' has goods receipts and cannot be deleted — set active:false to retire"
                    + " them instead.");
          }
          repo.deleteById(orgId, id);
          log.info("Deleted supplier id={} orgId={}", id, orgId);
        });
  }

  /** Two spellings of one supplier are one AP balance split in half — so the fold is the key. */
  private void requireFoldedNameFree(
      SupplierRepository repo, UUID orgId, String name, UUID excludeId) {
    repo.findByFoldedName(orgId, name)
        .filter(other -> !other.getId().equals(excludeId))
        .ifPresent(
            other -> {
              throw new ConflictException(
                  "A supplier named '" + other.getName() + "' already exists");
            });
  }

  private static String requireName(String raw) {
    String name = raw == null ? null : raw.trim();
    if (name == null || name.isEmpty()) {
      throw new ValidationException("name is required");
    }
    if (name.length() > NAME_MAX) {
      throw new ValidationException("name must be at most " + NAME_MAX + " characters");
    }
    return name;
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
