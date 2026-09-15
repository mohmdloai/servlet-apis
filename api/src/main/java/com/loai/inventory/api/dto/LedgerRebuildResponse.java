package com.loai.inventory.api.dto;

import com.loai.inventory.service.LedgerService;
import java.util.Map;

/** {@code POST /api/orgs/{orgId}/ledger/rebuild[?reset=true]} — what the run removed and posted. */
public record LedgerRebuildResponse(
    boolean reset, int removed, int posted, Map<String, Integer> postedByKind) {

  public static LedgerRebuildResponse from(boolean reset, LedgerService.Rebuilt r) {
    return new LedgerRebuildResponse(reset, r.removed(), r.posted().total(), r.posted().inserted());
  }
}
