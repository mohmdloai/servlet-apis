package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One channel-specific attempt to deliver a {@link Notification} (a supertable child). Each channel
 * also owns a subtype row (e.g. {@code notification_delivery_in_app}) created in the same txn.
 */
public class NotificationDelivery {
  private UUID id;
  private UUID notificationId;
  private NotificationChannel channel;
  private DeliveryStatus status;
  private int attempts;
  private String lastError;
  private OffsetDateTime sentAt;
  private OffsetDateTime deliveredAt;
  private OffsetDateTime failedAt;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public NotificationDelivery() {}

  public NotificationDelivery(
      UUID id,
      UUID notificationId,
      NotificationChannel channel,
      DeliveryStatus status,
      int attempts,
      String lastError,
      OffsetDateTime sentAt,
      OffsetDateTime deliveredAt,
      OffsetDateTime failedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.notificationId = notificationId;
    this.channel = channel;
    this.status = status;
    this.attempts = attempts;
    this.lastError = lastError;
    this.sentAt = sentAt;
    this.deliveredAt = deliveredAt;
    this.failedAt = failedAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getNotificationId() {
    return notificationId;
  }

  public void setNotificationId(UUID notificationId) {
    this.notificationId = notificationId;
  }

  public NotificationChannel getChannel() {
    return channel;
  }

  public void setChannel(NotificationChannel channel) {
    this.channel = channel;
  }

  public DeliveryStatus getStatus() {
    return status;
  }

  public void setStatus(DeliveryStatus status) {
    this.status = status;
  }

  public int getAttempts() {
    return attempts;
  }

  public void setAttempts(int attempts) {
    this.attempts = attempts;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String lastError) {
    this.lastError = lastError;
  }

  public OffsetDateTime getSentAt() {
    return sentAt;
  }

  public void setSentAt(OffsetDateTime sentAt) {
    this.sentAt = sentAt;
  }

  public OffsetDateTime getDeliveredAt() {
    return deliveredAt;
  }

  public void setDeliveredAt(OffsetDateTime deliveredAt) {
    this.deliveredAt = deliveredAt;
  }

  public OffsetDateTime getFailedAt() {
    return failedAt;
  }

  public void setFailedAt(OffsetDateTime failedAt) {
    this.failedAt = failedAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(OffsetDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  @Override
  public String toString() {
    return "NotificationDelivery{id="
        + id
        + ", notificationId="
        + notificationId
        + ", channel="
        + channel
        + ", status="
        + status
        + "}";
  }
}
