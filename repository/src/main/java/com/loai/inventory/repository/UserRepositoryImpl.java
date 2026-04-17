package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.USER_SYSTEM_ROLE;
import static com.loai.inventory.repository.generated.Tables.USER_TENANT_ROLE;

import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.domain.model.TenantRole;
import com.loai.inventory.domain.model.UserTenantRole;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.repository.generated.tables.records.AppUserRecord;
import com.loai.inventory.repository.generated.tables.records.UserTenantRoleRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UserRepositoryImpl implements UserRepository {
  private static final Logger log = LoggerFactory.getLogger(UserRepositoryImpl.class);
  private final DSLContext dsl;

  public UserRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<AppUser> findById(UUID id) {
    return dsl.selectFrom(APP_USER).where(APP_USER.ID.eq(id)).fetchOptional().map(this::toAppUser);
  }

  @Override
  public Optional<AppUser> findByEmail(String email) {
    return dsl.selectFrom(APP_USER)
        .where(APP_USER.EMAIL.eq(email))
        .fetchOptional()
        .map(this::toAppUser);
  }

  @Override
  public AppUser insert(AppUser user) {
    AppUserRecord record =
        dsl.insertInto(APP_USER)
            .set(APP_USER.EMAIL, user.getEmail())
            .set(APP_USER.PASSWORD_HASH, user.getPasswordHash())
            .set(
                APP_USER.ACTOR_TYPE,
                com.loai.inventory.repository.generated.enums.ActorType.lookupLiteral(
                    user.getActorType().name()))
            .set(APP_USER.ACTIVE, user.isActive())
            .set(APP_USER.TOKEN_VERSION, user.getTokenVersion())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT into app_user returned no record");
    }
    log.debug("Inserted app_user id={} email={}", record.getId(), record.getEmail());
    return toAppUser(record);
  }

  @Override
  public AppUser update(AppUser user) {
    AppUserRecord record =
        dsl.update(APP_USER)
            .set(APP_USER.EMAIL, user.getEmail())
            .set(APP_USER.PASSWORD_HASH, user.getPasswordHash())
            .set(
                APP_USER.ACTOR_TYPE,
                com.loai.inventory.repository.generated.enums.ActorType.lookupLiteral(
                    user.getActorType().name()))
            .set(APP_USER.ACTIVE, user.isActive())
            .set(APP_USER.TOKEN_VERSION, user.getTokenVersion())
            .set(APP_USER.UPDATED_AT, OffsetDateTime.now())
            .where(APP_USER.ID.eq(user.getId()))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("UPDATE app_user returned no record");
    }
    log.debug("Updated app_user id={}", record.getId());
    return toAppUser(record);
  }

  @Override
  public int incrementTokenVersion(UUID userId) {
    AppUserRecord record =
        dsl.update(APP_USER)
            .set(APP_USER.TOKEN_VERSION, APP_USER.TOKEN_VERSION.plus(1))
            .set(APP_USER.UPDATED_AT, OffsetDateTime.now())
            .where(APP_USER.ID.eq(userId))
            .returning(APP_USER.TOKEN_VERSION)
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException(
          "incrementTokenVersion returned no record for user " + userId);
    }
    return record.getTokenVersion();
  }

  @Override
  public int getTokenVersion(UUID userId) {
    Integer version =
        dsl.select(APP_USER.TOKEN_VERSION)
            .from(APP_USER)
            .where(APP_USER.ID.eq(userId))
            .fetchOneInto(Integer.class);
    if (version == null) {
      throw new IllegalStateException("No app_user found for id " + userId);
    }
    return version;
  }

  @Override
  public List<UserTenantRole> findTenantRoles(UUID userId) {
    return dsl.selectFrom(USER_TENANT_ROLE)
        .where(USER_TENANT_ROLE.USER_ID.eq(userId))
        .fetch()
        .map(this::toUserTenantRole);
  }

  @Override
  public Set<SystemRole> findSystemRoles(UUID userId) {
    return dsl.selectFrom(USER_SYSTEM_ROLE)
        .where(USER_SYSTEM_ROLE.USER_ID.eq(userId))
        .fetchSet(r -> SystemRole.valueOf(r.getRole().getLiteral()));
  }

  private AppUser toAppUser(AppUserRecord r) {
    return new AppUser(
        r.getId(),
        r.getEmail(),
        r.getPasswordHash(),
        ActorType.valueOf(r.getActorType().getLiteral()),
        r.getActive(),
        r.getTokenVersion(),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }

  private UserTenantRole toUserTenantRole(UserTenantRoleRecord r) {
    return new UserTenantRole(
        r.getUserId(), r.getTenantId(), TenantRole.valueOf(r.getRole().getLiteral()));
  }
}
