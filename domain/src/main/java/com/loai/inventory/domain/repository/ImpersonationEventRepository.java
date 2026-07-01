package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.ImpersonationEvent;

/** Append-only Layer-1 audit ledger for impersonation START/STOP events. */
public interface ImpersonationEventRepository {

  /** Persist one START or STOP event. {@code created_at} is set by the database default. */
  void insert(ImpersonationEvent event);
}
