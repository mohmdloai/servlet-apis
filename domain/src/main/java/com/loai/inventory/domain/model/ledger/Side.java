package com.loai.inventory.domain.model.ledger;

/**
 * A journal line's side. Distinct on purpose from {@code PaymentTransaction.direction}
 * (money-in/money-out at the provider): this is the accounting posting the
 * sys-analysis/system/accounting-future.md note reserved the word "debit" for.
 */
public enum Side {
  DR,
  CR
}
