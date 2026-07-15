package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.ListingComment;
import java.time.OffsetDateTime;

/**
 * One public Q&amp;A pair (slice R2, epic §6): exactly {@code display_name} (frozen at write),
 * {@code body}, {@code created_at}, {@code reply_body} and {@code replied_at} — never a customer
 * id, {@code replied_by}, or any internal id. ANSWERED-only by the service, so both reply fields
 * are always present.
 */
public class PublicCommentResponse {

  private String displayName;
  private String body;
  private OffsetDateTime createdAt;
  private String replyBody;
  private OffsetDateTime repliedAt;

  private PublicCommentResponse() {}

  public static PublicCommentResponse from(ListingComment comment) {
    PublicCommentResponse r = new PublicCommentResponse();
    r.displayName = comment.getDisplayName();
    r.body = comment.getBody();
    r.createdAt = comment.getCreatedAt();
    r.replyBody = comment.getReplyBody();
    r.repliedAt = comment.getRepliedAt();
    return r;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getBody() {
    return body;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public String getReplyBody() {
    return replyBody;
  }

  public OffsetDateTime getRepliedAt() {
    return repliedAt;
  }
}
