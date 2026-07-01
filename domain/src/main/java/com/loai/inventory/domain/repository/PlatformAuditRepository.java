package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.PlatformAuditEvent;

/** Append-only writer for the platform-tier audit ledger (see {@code PlatformAuditEvent}). */
public interface PlatformAuditRepository {
  /** Insert one audit row. {@code created_at} is set by the database default. */
  void insert(PlatformAuditEvent event);
}
