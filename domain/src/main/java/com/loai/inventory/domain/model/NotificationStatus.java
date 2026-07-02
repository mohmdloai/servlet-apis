package com.loai.inventory.domain.model;

/**
 * Lifecycle of the channel-agnostic notification event. Terminal once every delivery is attempted.
 */
public enum NotificationStatus {
  PENDING,
  DISPATCHED
}
