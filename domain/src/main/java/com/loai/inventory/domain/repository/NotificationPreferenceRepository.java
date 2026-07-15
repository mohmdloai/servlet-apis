package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.NotificationChannel;
import com.loai.inventory.domain.model.NotificationPreference;
import com.loai.inventory.domain.model.RecipientType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for notification preferences (the opt-out layer). {@link #resolveEnabled} runs on the
 * producer's hot path (inside the business txn); the staff CRUD + unsubscribe upserts run on their
 * own transactions.
 */
public interface NotificationPreferenceRepository {

  /**
   * The effective {@code enabled} flag for {@code (subject, type, channel)}: an exact-type row wins
   * over an {@code ALL} row; empty means no row matched (caller defaults to enabled). {@code
   * subjectId} is the user id or customer id per {@code subjectType}.
   */
  Optional<Boolean> resolveEnabled(
      UUID orgId,
      RecipientType subjectType,
      UUID subjectId,
      String type,
      NotificationChannel channel);

  /** Every preference row for one staff user, for the read endpoint. */
  List<NotificationPreference> findByUser(UUID orgId, UUID userId);

  /** Every preference row for one customer, for the portal read endpoint (slice P5). */
  List<NotificationPreference> findByCustomer(UUID orgId, UUID customerId);

  /** Upsert one USER preference (conflict on {@code (org_id, user_id, type, channel)}). */
  void upsertUser(
      UUID orgId, UUID userId, String type, NotificationChannel channel, boolean enabled);

  /** Upsert one CUSTOMER preference (conflict on {@code (org_id, customer_id, type, channel)}). */
  void upsertCustomer(
      UUID orgId, UUID customerId, String type, NotificationChannel channel, boolean enabled);
}
