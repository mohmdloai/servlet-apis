package com.loai.inventory.domain.model;

/**
 * Source of a {@link PaymentTransaction}. The DB enum {@code payment_provider} uses lowercase
 * labels, so — unlike the order enums whose labels match {@code name()} — this enum carries an
 * explicit {@link #dbLiteral} for round-tripping through the jOOQ-generated enum.
 */
public enum PaymentProvider {
  INSTAPAY_MANUAL("instapay_manual"),
  INSTAPAY_IN_STORE("instapay_in_store"),
  CASH("cash");
  // 'paymob_card' exists on the DB enum since V98 (paymob_connect.md) but gets no constant here
  // until slice 2 (V99) actually writes a payment_transaction with it — see
  // docs/paymob-card-epic.md.

  private final String dbLiteral;

  PaymentProvider(String dbLiteral) {
    this.dbLiteral = dbLiteral;
  }

  public String dbLiteral() {
    return dbLiteral;
  }

  /** conventional Java array (PaymentProvider[]) of all constants is available via values() */
  public static PaymentProvider fromDbLiteral(String literal) {
    for (PaymentProvider p : values()) {
      if (p.dbLiteral.equals(literal)) {
        return p;
      }
    }
    throw new IllegalArgumentException("Unknown payment_provider literal: " + literal);
  }
}
