package com.loai.inventory.domain.model;

/**
 * Per-channel delivery lifecycle. SENT/DELIVERED/FAILED are terminal for dispatch purposes.
 *
 * <p>{@link #SENDING} is a <b>claim</b>, not a report about the provider: it means a worker has
 * taken this row and is talking to SMTP right now. It exists so the send can happen
 * <em>outside</em> the database transaction — without it, the only state meaning "mine" is the row
 * lock, which forces the whole SMTP round-trip to run inside the transaction that observed PENDING
 * (D4). A row left SENDING by a crashed worker is stranded, and the reaper returns it to PENDING
 * using {@code claimed_at} as the lease.
 */
public enum DeliveryStatus {
  PENDING,
  SENDING,
  SENT,
  DELIVERED,
  FAILED
}
