package com.loai.inventory.api.dto;

import com.loai.inventory.service.StorefrontService.StorefrontShortage;
import java.util.List;

/**
 * The {@code 409} body for an anonymous checkout shortage: the standard {@code
 * status/error/message} shape plus {@code shortages}, each re-keyed to {@code listing_slug} +
 * {@code title} — <b>never</b> {@code product_id} (the no-leak invariant, {@code
 * public_checkout.md} §No-leak).
 */
public class PublicCheckoutError {

  private final int status = 409;
  private final String error = "Conflict";
  private final String message;
  private final List<Shortage> shortages;

  private PublicCheckoutError(String message, List<Shortage> shortages) {
    this.message = message;
    this.shortages = shortages;
  }

  public static PublicCheckoutError of(String message, List<StorefrontShortage> shortages) {
    return new PublicCheckoutError(
        message,
        shortages.stream()
            .map(s -> new Shortage(s.listingSlug(), s.title(), s.requested(), s.available()))
            .toList());
  }

  public int getStatus() {
    return status;
  }

  public String getError() {
    return error;
  }

  public String getMessage() {
    return message;
  }

  public List<Shortage> getShortages() {
    return shortages;
  }

  /** A per-line shortage — slug-keyed, no internal id. */
  public record Shortage(String listingSlug, String title, int requested, int available) {}
}
