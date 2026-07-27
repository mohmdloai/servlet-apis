package com.loai.inventory.service.platform;

import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.PlatformQueueKind;
import com.loai.inventory.domain.model.PlatformQueueRow;
import com.loai.inventory.domain.repository.PlatformQueueRepository;
import com.loai.inventory.domain.repository.PlatformQueueRepositoryFactory;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The cross-org queue reads behind {@code GET /api/admin/queues/{kind}} ({@code
 * stories/platform_queues.md}) — the drill-down under the overview's five backlog tiles.
 *
 * <p>Paging and kind-parsing only: the queue definitions live one layer down, in {@code
 * PlatformQueuePredicates}, shared with the overview's counts so a tile and its list cannot
 * disagree. Nothing here narrows by tenant status — a suspended merchant's unpaid refunds are the
 * ones nobody else is working.
 */
public class PlatformQueueService {

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  private final DSLContext dsl;
  private final PlatformQueueRepositoryFactory queueRepoFactory;

  public PlatformQueueService(DSLContext dsl, PlatformQueueRepositoryFactory queueRepoFactory) {
    this.dsl = dsl;
    this.queueRepoFactory = queueRepoFactory;
  }

  /** One page of a queue, with the total so the client can page and cross-check its tile. */
  public record QueuePage(List<PlatformQueueRow> rows, long total, int page, int size) {}

  /**
   * Parse the {@code {kind}} path segment, or 400 naming all five.
   *
   * <p>A 400 rather than a 404 on purpose: {@code kind} is an enum value that happens to sit in the
   * path, so it follows this codebase's {@code ?status=} convention (unknown → a cause-naming 400)
   * rather than its unknown-resource convention. A bare {@code GET /api/admin/queues} lands here
   * too, mirroring the reserved-route 400 on {@code GET /sales-orders} and {@code GET
   * /credit-notes}.
   */
  public static PlatformQueueKind parseKind(String raw) {
    try {
      return PlatformQueueKind.fromWire(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException(
          "Unknown queue: '"
              + (raw == null ? "" : raw)
              + "'. Expected one of: "
              + PlatformQueueKind.allWireValues());
    }
  }

  /**
   * A page of {@code kind}, oldest-first, optionally narrowed to one tenant. {@code page} is
   * 0-based; {@code size} is clamped to {@code [1, MAX_PAGE_SIZE]}, and the offset is computed with
   * {@link PlatformOrgService#safeOffset} so a huge page number cannot overflow into a negative
   * OFFSET.
   *
   * <p>An {@code orgId} no tenant holds yields an empty page with {@code total = 0} — it is a
   * filter, not a lookup.
   */
  public QueuePage list(PlatformQueueKind kind, UUID orgId, int page, int size) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int offset = PlatformOrgService.safeOffset(p, s);

    PlatformQueueRepository repo = queueRepoFactory.create(dsl);
    List<PlatformQueueRow> rows = repo.page(kind, orgId, offset, s);
    long total = repo.count(kind, orgId);
    return new QueuePage(rows, total, p, s);
  }
}
