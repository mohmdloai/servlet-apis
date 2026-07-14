package com.loai.inventory.domain.model;

import java.util.Locale;

/**
 * The closed set of storefront text-page kinds (customization epic slice C4). A fixed kind has a
 * fixed, frontend-localized title and a well-known route ({@code /pages/about}, {@code
 * /pages/policies}) — the enum is the contract, so a well-formed path with an unknown kind value is
 * a 400 (the value isn't in the set), not a 404 (the route is fine). Persisted as the lowercase
 * {@link #wire()} string in {@code storefront_page.kind} (a TEXT CHECK column, not a PG enum, so a
 * new kind is one CHECK value + one catalog key). See {@code stories/storefront_pages.md}.
 */
public enum PageKind {
  ABOUT,
  POLICIES;

  /** The lowercase DB / wire form ({@code about} / {@code policies}). */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Parse the wire form; an unknown value throws (the caller maps it to a 400). */
  public static PageKind fromWire(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("kind is required");
    }
    return switch (raw.trim().toLowerCase(Locale.ROOT)) {
      case "about" -> ABOUT;
      case "policies" -> POLICIES;
      default -> throw new IllegalArgumentException("Unknown page kind: " + raw);
    };
  }
}
