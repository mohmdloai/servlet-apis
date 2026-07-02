package com.loai.inventory.domain.model;

/**
 * What a {@link CustomerMagicToken} unlocks. Stored as {@code name()} in the {@code
 * customer_magic_token.purpose} TEXT column (CHECK-constrained). Phase 2 issues {@link #VIEW_ORDER}
 * only; the others are reserved for later slices (invoice emails, unsubscribe).
 */
public enum MagicTokenPurpose {
  VIEW_ORDER,
  VIEW_INVOICE,
  UNSUBSCRIBE
}
