package com.loai.inventory.api.dto;

import com.loai.inventory.domain.repository.ListingCommentRepository.AdminComment;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One staff answer-worklist row (slice R2): the comment plus the customer context staff already see
 * in the CRM (name/email) and the listing title. Never serialized on the public plane.
 */
public class CommentAdminResponse {

  private UUID id;
  private String listingTitle;
  private String body;
  private String displayName;
  private String customerName;
  private String customerEmail;
  private String status;
  private String replyBody;
  private OffsetDateTime repliedAt;
  private OffsetDateTime createdAt;

  private CommentAdminResponse() {}

  public static CommentAdminResponse from(AdminComment row) {
    CommentAdminResponse r = new CommentAdminResponse();
    r.id = row.comment().getId();
    r.listingTitle = row.listingTitle();
    r.body = row.comment().getBody();
    r.displayName = row.comment().getDisplayName();
    r.customerName = row.customerName();
    r.customerEmail = row.customerEmail();
    r.status = row.comment().getStatus().name();
    r.replyBody = row.comment().getReplyBody();
    r.repliedAt = row.comment().getRepliedAt();
    r.createdAt = row.comment().getCreatedAt();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public String getListingTitle() {
    return listingTitle;
  }

  public String getBody() {
    return body;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getCustomerName() {
    return customerName;
  }

  public String getCustomerEmail() {
    return customerEmail;
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
