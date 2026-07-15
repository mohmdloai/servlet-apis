package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.InAppFeedItem;
import com.loai.inventory.domain.model.Notification;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One customer-portal feed entry (slice P5) — the customer-safe projection of an in-app delivery.
 * Deliberately leaner than the staff {@link NotificationResponse}: no {@code source_type}/{@code
 * source_id} (an internal row id must never cross the public boundary) and no {@code link_target}
 * (for customer notifications that column holds the emailed magic-link URL; the portal deep-links
 * via {@code order_number} instead — the session is the capability). {@code id} stays — it is the
 * addressing handle for read/dismiss. Jackson serializes to snake_case.
 */
public class PortalNotificationResponse {
  private UUID id;
  private String type;
  private String title;
  private String body;
  private String orderNumber;
  private String listingSlug;
  private OffsetDateTime createdAt;
  private OffsetDateTime readAt;

  private PortalNotificationResponse() {}

  /**
   * {@code orderNumber} and {@code listingSlug} are pre-extracted from the notification payload by
   * the caller (both nullable — order events carry the former, {@code COMMENT_REPLIED} the latter,
   * slice R2; the feed deep-links accordingly).
   */
  public static PortalNotificationResponse from(
      InAppFeedItem item, String orderNumber, String listingSlug) {
    Notification n = item.notification();
    PortalNotificationResponse r = new PortalNotificationResponse();
    r.id = n.getId();
    r.type = n.getType();
    r.title = n.getTitle();
    r.body = n.getBody();
    r.orderNumber = orderNumber;
    r.listingSlug = listingSlug;
    r.createdAt = n.getCreatedAt();
    r.readAt = item.readAt();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public String getType() {
    return type;
  }

  public String getTitle() {
    return title;
  }

  public String getBody() {
    return body;
  }

  public String getOrderNumber() {
    return orderNumber;
  }

  public String getListingSlug() {
    return listingSlug;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getReadAt() {
    return readAt;
  }
}
