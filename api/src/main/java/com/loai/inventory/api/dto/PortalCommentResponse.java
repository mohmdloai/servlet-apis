package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.ListingComment;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The customer's own comment on the portal plane (slice R2): the row id (the delete handle), the
 * listing's public identity, the message, its status and — once answered — the store's reply.
 * {@code replied_by} never crosses (the merchant speaks as the store).
 */
public class PortalCommentResponse {

  private UUID id;
  private String listingSlug;
  private String listingTitle;
  private String body;
  private String status;
  private String replyBody;
  private OffsetDateTime repliedAt;
  private OffsetDateTime createdAt;

  private PortalCommentResponse() {}

  public static PortalCommentResponse from(
      ListingComment comment, String listingSlug, String listingTitle) {
    PortalCommentResponse r = new PortalCommentResponse();
    r.id = comment.getId();
    r.listingSlug = listingSlug;
    r.listingTitle = listingTitle;
    r.body = comment.getBody();
    r.status = comment.getStatus().name();
    r.replyBody = comment.getReplyBody();
    r.repliedAt = comment.getRepliedAt();
    r.createdAt = comment.getCreatedAt();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public String getListingSlug() {
    return listingSlug;
  }

  public String getListingTitle() {
    return listingTitle;
  }

  public String getBody() {
    return body;
  }

  public String getStatus() {
    return status;
  }

  public String getReplyBody() {
    return replyBody;
  }

  public OffsetDateTime getRepliedAt() {
    return repliedAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
