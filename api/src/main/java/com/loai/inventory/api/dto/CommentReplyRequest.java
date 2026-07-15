package com.loai.inventory.api.dto;

/**
 * Body of {@code POST /api/orgs/{orgId}/comments/{id}/reply} (slice R2): the one official answer
 * (1..2000 chars). Replying publishes the pair; a re-reply edits the published answer.
 */
public class CommentReplyRequest {

  private String body;

  private CommentReplyRequest() {}

  public String getBody() {
    return body;
  }
}
