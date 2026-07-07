package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.PlatformAuditEntry;
import com.loai.inventory.domain.model.PlatformAuditEvent;
import java.util.List;
import java.util.UUID;

/** Append-only writer plus paged reader for the platform-tier audit ledger (PG2). */
public interface PlatformAuditRepository {
  /** Insert one audit row. {@code created_at} is set by the database default. */
  void insert(PlatformAuditEvent event);

  /**
   * A page of audit rows newest-first ({@code created_at DESC, id DESC}), optionally narrowed by
   * {@code targetType} and/or {@code actorId} (a {@code null} filter is ignored).
   */
  List<PlatformAuditEntry> find(int offset, int limit, String targetType, UUID actorId);

  /** Total row count matching the same optional filters. */
  long count(String targetType, UUID actorId);
}
