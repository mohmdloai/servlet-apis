package com.loai.inventory.domain.model;

/**
 * What area of the product a {@link SupportTicket} is about — the merchant's pick, by area rather
 * than by kind ("bug" vs "question" is the desk's judgement, not the merchant's). Open TEXT in the
 * column; this enum owns the vocabulary.
 */
public enum TicketCategory {
  ACCOUNT,
  ORDERS,
  PAYMENTS,
  CATALOG,
  STOREFRONT,
  DEVICES,
  OTHER;

  /**
   * Parse a request value; unknown → {@code IllegalArgumentException} (the handler maps to 400).
   */
  public static TicketCategory parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("category is required");
    }
    return TicketCategory.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
  }
}
