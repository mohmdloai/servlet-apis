package com.loai.inventory.service;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The one question the money paths ask ({@code stories/cash_shift.md} §A stamp on the transaction):
 * which cash shift does this drawer event belong to? Answered inside the caller's transaction,
 * before the row is written, so the stamp and the event commit together.
 */
@FunctionalInterface
public interface CashShiftStamper {

  /**
   * The org's open shift id for a counter event by {@code actorId} — opening one automatically
   * (float = the last closed count) when the org allows it, or throwing {@code
   * ShiftRequiredException} when the org gates and none is open. {@code null} means "no shift
   * feature here" (the {@link #NONE} wiring).
   */
  UUID shiftForCounterInTx(org.jooq.DSLContext txDsl, UUID orgId, UUID actorId, OffsetDateTime now);

  /** Stamps nothing, gates nothing — the wiring for tests and for a build without the feature. */
  CashShiftStamper NONE = (txDsl, orgId, actorId, now) -> null;
}
