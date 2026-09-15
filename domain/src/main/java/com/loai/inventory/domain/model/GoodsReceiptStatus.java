package com.loai.inventory.domain.model;

/**
 * POSTED → VOIDED, one way. A receipt is done the moment it is keyed; a void is an accounting event
 * with an author and a reason, so a second one is a 409, not a replay.
 */
public enum GoodsReceiptStatus {
  POSTED,
  VOIDED
}
