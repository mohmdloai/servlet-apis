package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_PREFERENCE;

import com.loai.inventory.domain.model.NotificationChannel;
import com.loai.inventory.domain.model.NotificationPreference;
import com.loai.inventory.domain.model.RecipientType;
import com.loai.inventory.domain.repository.NotificationPreferenceRepository;
import com.loai.inventory.repository.generated.tables.records.NotificationPreferenceRecord;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * jOOQ persistence for notification preferences. {@code subject_type}/{@code channel} are TEXT; the
 * upserts are update-then-insert against the per-subject partial unique index.
 */
public final class NotificationPreferenceRepositoryImpl
    implements NotificationPreferenceRepository {

  private final DSLContext dsl;

  public NotificationPreferenceRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<Boolean> resolveEnabled(
      UUID orgId,
      RecipientType subjectType,
      UUID subjectId,
      String type,
      NotificationChannel channel) {
    return dsl.select(NOTIFICATION_PREFERENCE.ENABLED)
        .from(NOTIFICATION_PREFERENCE)
        .where(NOTIFICATION_PREFERENCE.ORG_ID.eq(orgId))
        .and(subjectCondition(subjectType, subjectId))
        .and(NOTIFICATION_PREFERENCE.CHANNEL.eq(channel.dbValue()))
        .and(NOTIFICATION_PREFERENCE.TYPE.in(type, NotificationPreference.ALL_TYPES))
        // An exact-type row wins over the 'ALL' wildcard (true sorts first under DESC in Postgres).
        .orderBy(DSL.field(NOTIFICATION_PREFERENCE.TYPE.eq(type)).desc())
        .limit(1)
        .fetchOptional(NOTIFICATION_PREFERENCE.ENABLED);
  }

  @Override
  public List<NotificationPreference> findByUser(UUID orgId, UUID userId) {
    return dsl.selectFrom(NOTIFICATION_PREFERENCE)
        .where(NOTIFICATION_PREFERENCE.ORG_ID.eq(orgId))
        .and(NOTIFICATION_PREFERENCE.SUBJECT_TYPE.eq(RecipientType.USER.name()))
        .and(NOTIFICATION_PREFERENCE.USER_ID.eq(userId))
        .orderBy(NOTIFICATION_PREFERENCE.TYPE.asc(), NOTIFICATION_PREFERENCE.CHANNEL.asc())
        .fetch()
        .map(this::toPreference);
  }

  @Override
  public void upsertUser(
      UUID orgId, UUID userId, String type, NotificationChannel channel, boolean enabled) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    // Atomic upsert: a single INSERT ... ON CONFLICT against the partial unique index. The WHERE
    // repeats the index predicate (subject_type='USER') so Postgres infers that exact index — a
    // plain UPDATE-then-INSERT would let two concurrent first-writes both insert and collide.
    dsl.insertInto(NOTIFICATION_PREFERENCE)
        .set(NOTIFICATION_PREFERENCE.ORG_ID, orgId)
        .set(NOTIFICATION_PREFERENCE.SUBJECT_TYPE, RecipientType.USER.name())
        .set(NOTIFICATION_PREFERENCE.USER_ID, userId)
        .set(NOTIFICATION_PREFERENCE.TYPE, type)
        .set(NOTIFICATION_PREFERENCE.CHANNEL, channel.dbValue())
        .set(NOTIFICATION_PREFERENCE.ENABLED, enabled)
        .onConflict(
            NOTIFICATION_PREFERENCE.ORG_ID,
            NOTIFICATION_PREFERENCE.USER_ID,
            NOTIFICATION_PREFERENCE.TYPE,
            NOTIFICATION_PREFERENCE.CHANNEL)
        .where(NOTIFICATION_PREFERENCE.SUBJECT_TYPE.eq(RecipientType.USER.name()))
        .doUpdate()
        .set(NOTIFICATION_PREFERENCE.ENABLED, enabled)
        .set(NOTIFICATION_PREFERENCE.UPDATED_AT, now)
        .execute();
  }

  @Override
  public void upsertCustomer(
      UUID orgId, UUID customerId, String type, NotificationChannel channel, boolean enabled) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    // Atomic upsert against the CUSTOMER partial unique index (see upsertUser).
    dsl.insertInto(NOTIFICATION_PREFERENCE)
        .set(NOTIFICATION_PREFERENCE.ORG_ID, orgId)
        .set(NOTIFICATION_PREFERENCE.SUBJECT_TYPE, RecipientType.CUSTOMER.name())
        .set(NOTIFICATION_PREFERENCE.CUSTOMER_ID, customerId)
        .set(NOTIFICATION_PREFERENCE.TYPE, type)
        .set(NOTIFICATION_PREFERENCE.CHANNEL, channel.dbValue())
        .set(NOTIFICATION_PREFERENCE.ENABLED, enabled)
        .onConflict(
            NOTIFICATION_PREFERENCE.ORG_ID,
            NOTIFICATION_PREFERENCE.CUSTOMER_ID,
            NOTIFICATION_PREFERENCE.TYPE,
            NOTIFICATION_PREFERENCE.CHANNEL)
        .where(NOTIFICATION_PREFERENCE.SUBJECT_TYPE.eq(RecipientType.CUSTOMER.name()))
        .doUpdate()
        .set(NOTIFICATION_PREFERENCE.ENABLED, enabled)
        .set(NOTIFICATION_PREFERENCE.UPDATED_AT, now)
        .execute();
  }

  private Condition subjectCondition(RecipientType subjectType, UUID subjectId) {
    return switch (subjectType) {
      case USER ->
          NOTIFICATION_PREFERENCE
              .SUBJECT_TYPE
              .eq(RecipientType.USER.name())
              .and(NOTIFICATION_PREFERENCE.USER_ID.eq(subjectId));
      case CUSTOMER ->
          NOTIFICATION_PREFERENCE
              .SUBJECT_TYPE
              .eq(RecipientType.CUSTOMER.name())
              .and(NOTIFICATION_PREFERENCE.CUSTOMER_ID.eq(subjectId));
    };
  }

  private NotificationPreference toPreference(NotificationPreferenceRecord r) {
    return new NotificationPreference(
        r.getId(),
        r.getOrgId(),
        RecipientType.valueOf(r.getSubjectType()),
        r.getUserId(),
        r.getCustomerId(),
        r.getType(),
        NotificationChannel.fromDbValue(r.getChannel()),
        r.getEnabled(),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }
}
