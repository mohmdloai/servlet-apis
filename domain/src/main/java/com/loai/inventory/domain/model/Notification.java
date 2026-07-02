package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The channel-agnostic notification event (the supertable parent). One notification fans out to
 * many {@link NotificationDelivery} rows — one per channel. The recipient is polymorphic: exactly
 * one of {@code recipientUserId} / {@code recipientCustomerId} is set, per {@link #recipientType}.
 *
 * <p>{@code payloadJson} carries the already-serialized JSON (or null) for the {@code payload
 * jsonb} column; the domain module stays free of any JSON library. {@code id}/{@code createdAt} are
 * DB-assigned on insert.
 */
public class Notification {
  private UUID id;
  private UUID orgId;
  private RecipientType recipientType;
  private UUID recipientUserId;
  private UUID recipientCustomerId;
  private String type;
  private String title;
  private String body;
  private String sourceType;
  private UUID sourceId;
  private NotificationStatus status;
  private String payloadJson;
  private OffsetDateTime createdAt;
  private OffsetDateTime dispatchedAt;

  public Notification() {}

  public Notification(
      UUID id,
      UUID orgId,
      RecipientType recipientType,
      UUID recipientUserId,
      UUID recipientCustomerId,
      String type,
      String title,
      String body,
      String sourceType,
      UUID sourceId,
      NotificationStatus status,
      String payloadJson,
      OffsetDateTime createdAt,
      OffsetDateTime dispatchedAt) {
    this.id = id;
    this.orgId = orgId;
    this.recipientType = recipientType;
    this.recipientUserId = recipientUserId;
    this.recipientCustomerId = recipientCustomerId;
    this.type = type;
    this.title = title;
    this.body = body;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.status = status;
    this.payloadJson = payloadJson;
    this.createdAt = createdAt;
    this.dispatchedAt = dispatchedAt;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public void setOrgId(UUID orgId) {
    this.orgId = orgId;
  }

  public RecipientType getRecipientType() {
    return recipientType;
  }

  public void setRecipientType(RecipientType recipientType) {
    this.recipientType = recipientType;
  }

  public UUID getRecipientUserId() {
    return recipientUserId;
  }

  public void setRecipientUserId(UUID recipientUserId) {
    this.recipientUserId = recipientUserId;
  }

  public UUID getRecipientCustomerId() {
    return recipientCustomerId;
  }

  public void setRecipientCustomerId(UUID recipientCustomerId) {
    this.recipientCustomerId = recipientCustomerId;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getBody() {
    return body;
  }

  public void setBody(String body) {
    this.body = body;
  }

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
  }

  public UUID getSourceId() {
    return sourceId;
  }

  public void setSourceId(UUID sourceId) {
    this.sourceId = sourceId;
  }

  public NotificationStatus getStatus() {
    return status;
  }

  public void setStatus(NotificationStatus status) {
    this.status = status;
  }

  public String getPayloadJson() {
    return payloadJson;
  }

  public void setPayloadJson(String payloadJson) {
    this.payloadJson = payloadJson;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public OffsetDateTime getDispatchedAt() {
    return dispatchedAt;
  }

  public void setDispatchedAt(OffsetDateTime dispatchedAt) {
    this.dispatchedAt = dispatchedAt;
  }

  @Override
  public String toString() {
    return "Notification{id="
        + id
        + ", orgId="
        + orgId
        + ", type='"
        + type
        + "', status="
        + status
        + "}";
  }
}
