package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An opt-out row: {@code enabled=false} suppresses a {@code (subject, type, channel)} delivery.
 * Absence of a row means enabled (see {@code docs/notifications-plan.md} §4). The subject is
 * polymorphic — a {@code USER} or a {@code CUSTOMER}, exactly one id set. {@code type} is a {@link
 * NotificationType} name or {@link #ALL_TYPES}.
 */
public record NotificationPreference(
    UUID id,
    UUID orgId,
    RecipientType subjectType,
    UUID userId,
    UUID customerId,
    String type,
    NotificationChannel channel,
    boolean enabled,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

  /** Wildcard {@code type}: matches every notification type on the given channel. */
  public static final String ALL_TYPES = "ALL";
}
