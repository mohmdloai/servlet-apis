package com.loai.inventory.domain.model;

/**
 * Source of a {@link PaymentTransaction}. The DB enum {@code payment_provider} uses lowercase
 * labels, so — unlike the order enums whose labels match {@code name()} — this enum carries an
 * explicit {@link #dbLiteral} for round-tripping through the jOOQ-generated enum.
 */
public enum PaymentProvider {
  INSTAPAY_MANUAL("instapay_manual"),
  INSTAPAY_IN_STORE("instapay_in_store"),
  CASH("cash"),
  /**
   * An online card payment through the merchant's own Paymob account ({@code
   * docs/paymob-card-epic.md}). The DB value dates from V98; the constant arrived with slice 2
   * (V99), the first thing to write a transaction with it — recorded by the webhook, VERIFIED with
   * {@code verified_by IS NULL} (the gateway did it). Online only: the counter's card path is the
   * epic's unwritten slice 4 and gets its own value, because a card at a terminal shares nothing
   * with this but the word.
   */
  PAYMOB_CARD("paymob_card");

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
