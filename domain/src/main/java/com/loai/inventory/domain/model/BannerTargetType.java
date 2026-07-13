package com.loai.inventory.domain.model;

import java.util.Locale;

/**
 * Where a {@link StorefrontBanner} sends a shopper — a <b>structured, slug-keyed</b> target within
 * the same org (a category or a listing), never a free-text href (customization epic §2, so an
 * absolute URL / open redirect is unrepresentable by construction). Persisted as the lowercase
 * {@link #wire()} string in {@code storefront_banner.target_type} (a TEXT CHECK column, not a PG
 * enum).
 */
public enum BannerTargetType {
  CATEGORY,
  LISTING;

  /** The lowercase DB / wire form ({@code category} / {@code listing}). */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Parse the wire form; an unknown value throws (the caller maps it to a 400). */
  public static BannerTargetType fromWire(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("target_type is required");
    }
    return switch (raw.trim().toLowerCase(Locale.ROOT)) {
      case "category" -> CATEGORY;
      case "listing" -> LISTING;
      default -> throw new IllegalArgumentException("Unknown target_type: " + raw);
    };
  }
}
