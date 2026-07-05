package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.USER_ORG_ROLE;
import static com.loai.inventory.repository.generated.Tables.USER_SYSTEM_ROLE;

import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.OrgMember;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.domain.model.UserOrgRole;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.repository.generated.tables.records.AppUserRecord;
import com.loai.inventory.repository.generated.tables.records.UserOrgRoleRecord;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
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
  public List<UserOrgRole> findOrgRoles(UUID userId) {
    return dsl.selectFrom(USER_ORG_ROLE)
        .where(USER_ORG_ROLE.USER_ID.eq(userId))
        .fetch()
        .map(this::toUserOrgRole);
  }

  @Override
  public Set<SystemRole> findSystemRoles(UUID userId) {
    return dsl.selectFrom(USER_SYSTEM_ROLE)
        .where(USER_SYSTEM_ROLE.USER_ID.eq(userId))
        .fetchSet(r -> SystemRole.valueOf(r.getRole().getLiteral()));
  }

  @Override
  public void insertOrgRole(UUID userId, UUID orgId, OrgRole role) {
    dsl.insertInto(USER_ORG_ROLE)
        .set(USER_ORG_ROLE.USER_ID, userId)
        .set(USER_ORG_ROLE.ORG_ID, orgId)
        .set(
            USER_ORG_ROLE.ROLE,
            com.loai.inventory.repository.generated.enums.OrgRole.lookupLiteral(role.name()))
        .onConflictDoNothing()
        .execute();
  }

  @Override
  public Set<UUID> findActiveUserIdsByOrgAndRoles(UUID orgId, Set<OrgRole> roles) {
    if (roles == null || roles.isEmpty()) {
      return Set.of();
    }
    List<com.loai.inventory.repository.generated.enums.OrgRole> literals =
        roles.stream()
            .map(r -> com.loai.inventory.repository.generated.enums.OrgRole.lookupLiteral(r.name()))
            .toList();
    return dsl.selectDistinct(USER_ORG_ROLE.USER_ID)
        .from(USER_ORG_ROLE)
        .join(APP_USER)
        .on(APP_USER.ID.eq(USER_ORG_ROLE.USER_ID))
        .where(USER_ORG_ROLE.ORG_ID.eq(orgId))
        .and(USER_ORG_ROLE.ROLE.in(literals))
        .and(APP_USER.ACTIVE.isTrue())
        .fetchSet(USER_ORG_ROLE.USER_ID);
  }

  @Override
  public void insertSystemRole(UUID userId, SystemRole role) {
    dsl.insertInto(USER_SYSTEM_ROLE)
        .set(USER_SYSTEM_ROLE.USER_ID, userId)
        .set(
            USER_SYSTEM_ROLE.ROLE,
            com.loai.inventory.repository.generated.enums.SystemRole.lookupLiteral(role.name()))
        .onConflictDoNothing()
        .execute();
  }

  @Override
  public int deleteSystemRole(UUID userId, SystemRole role) {
    return dsl.deleteFrom(USER_SYSTEM_ROLE)
        .where(USER_SYSTEM_ROLE.USER_ID.eq(userId))
        .and(
            USER_SYSTEM_ROLE.ROLE.eq(
                com.loai.inventory.repository.generated.enums.SystemRole.lookupLiteral(
                    role.name())))
        .execute();
  }

  @Override
  public int deleteOrgRole(UUID userId, UUID orgId, OrgRole role) {
    return dsl.deleteFrom(USER_ORG_ROLE)
        .where(USER_ORG_ROLE.USER_ID.eq(userId))
        .and(USER_ORG_ROLE.ORG_ID.eq(orgId))
        .and(
            USER_ORG_ROLE.ROLE.eq(
                com.loai.inventory.repository.generated.enums.OrgRole.lookupLiteral(role.name())))
        .execute();
  }

  @Override
  public List<OrgMember> findMembers(UUID orgId) {
    // Small result set (an org team), so we fetch (user, role) rows and group in Java rather than
    // wrestle array_agg of the generated enum. Ordered by email — the roster's stable display
    // order.
    Map<UUID, OrgMemberAccumulator> byUser = new LinkedHashMap<>();
    dsl.select(
            APP_USER.ID,
            APP_USER.EMAIL,
            APP_USER.DISPLAY_NAME,
            APP_USER.ACTIVE,
            APP_USER.CREATED_AT,
            USER_ORG_ROLE.ROLE)
        .from(USER_ORG_ROLE)
        .join(APP_USER)
        .on(APP_USER.ID.eq(USER_ORG_ROLE.USER_ID))
        .where(USER_ORG_ROLE.ORG_ID.eq(orgId))
        .orderBy(APP_USER.EMAIL.asc())
        .fetch()
        .forEach(
            r ->
                byUser
                    .computeIfAbsent(
                        r.get(APP_USER.ID),
                        k ->
                            new OrgMemberAccumulator(
                                r.get(APP_USER.EMAIL),
                                r.get(APP_USER.DISPLAY_NAME),
                                r.get(APP_USER.ACTIVE),
                                r.get(APP_USER.CREATED_AT)))
                    .roles
                    .add(OrgRole.valueOf(r.get(USER_ORG_ROLE.ROLE).getLiteral())));
    return byUser.entrySet().stream()
        .map(
            e ->
                new OrgMember(
                    e.getKey(),
                    e.getValue().email,
                    e.getValue().displayName,
                    e.getValue().roles,
                    e.getValue().active,
                    e.getValue().createdAt))
        .toList();
  }

  private static final class OrgMemberAccumulator {
    final String email;
    final String displayName;
    final boolean active;
    final OffsetDateTime createdAt;
    final Set<OrgRole> roles = new LinkedHashSet<>();

    OrgMemberAccumulator(
        String email, String displayName, boolean active, OffsetDateTime createdAt) {
      this.email = email;
      this.displayName = displayName;
      this.active = active;
      this.createdAt = createdAt;
    }
  }

  @Override
  public Set<OrgRole> findRolesInOrg(UUID userId, UUID orgId) {
    return dsl.select(USER_ORG_ROLE.ROLE)
        .from(USER_ORG_ROLE)
        .where(USER_ORG_ROLE.USER_ID.eq(userId))
        .and(USER_ORG_ROLE.ORG_ID.eq(orgId))
        .fetchSet(r -> OrgRole.valueOf(r.value1().getLiteral()));
  }

  @Override
  public Set<UUID> ownerIdsForUpdate(UUID orgId) {
    // FOR UPDATE so two concurrent OWNER de-privileges contend on the same rows and serialize -
    // closing the race where both read "2 owners", both pass the guard, and both commit to zero.
    return dsl.select(USER_ORG_ROLE.USER_ID)
        .from(USER_ORG_ROLE)
        .where(USER_ORG_ROLE.ORG_ID.eq(orgId))
        .and(
            USER_ORG_ROLE.ROLE.eq(
                com.loai.inventory.repository.generated.enums.OrgRole.lookupLiteral(
                    OrgRole.OWNER.name())))
        .forUpdate()
        .fetchSet(USER_ORG_ROLE.USER_ID);
  }

  @Override
  public int deleteAllOrgRoles(UUID userId, UUID orgId) {
    return dsl.deleteFrom(USER_ORG_ROLE)
        .where(USER_ORG_ROLE.USER_ID.eq(userId))
        .and(USER_ORG_ROLE.ORG_ID.eq(orgId))
        .execute();
  }

  @Override
  public List<AppUser> findAll(int offset, int limit, String emailQuery) {
    Condition condition =
        (emailQuery == null || emailQuery.isBlank())
            ? DSL.noCondition()
            : APP_USER.EMAIL.likeIgnoreCase(emailQuery.trim() + "%");
    return dsl.selectFrom(APP_USER)
        .where(condition)
        .orderBy(APP_USER.CREATED_AT.desc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(this::toAppUser);
  }

  @Override
  public long countAll(String emailQuery) {
    Condition condition =
        (emailQuery == null || emailQuery.isBlank())
            ? DSL.noCondition()
            : APP_USER.EMAIL.likeIgnoreCase(emailQuery.trim() + "%");
    return dsl.fetchCount(APP_USER, condition);
  }

  @Override
  public AppUser setActive(UUID userId, boolean active) {
    AppUserRecord record =
        dsl.update(APP_USER)
            .set(APP_USER.ACTIVE, active)
            .set(APP_USER.UPDATED_AT, OffsetDateTime.now())
            .where(APP_USER.ID.eq(userId))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new com.loai.inventory.common.exception.NotFoundException("User", userId);
    }
    return toAppUser(record);
  }

  @Override
  public void updatePasswordHash(UUID userId, String passwordHash) {
    int updated =
        dsl.update(APP_USER)
            .set(APP_USER.PASSWORD_HASH, passwordHash)
            .set(APP_USER.UPDATED_AT, OffsetDateTime.now())
            .where(APP_USER.ID.eq(userId))
            .execute();
    if (updated == 0) {
      throw new com.loai.inventory.common.exception.NotFoundException("User", userId);
    }
  }

  @Override
  public Set<UUID> activeAdminIdsForUpdate() {
    // Join user_system_role to app_user so we count only *active* admins, and FOR UPDATE so two
    // concurrent de-privileges contend on the same admin rows and serialize - closing the race
    // where both read "2 admins", both pass the guard, and both commit down to zero.
    return dsl.select(USER_SYSTEM_ROLE.USER_ID)
        .from(USER_SYSTEM_ROLE)
        .join(APP_USER)
        .on(APP_USER.ID.eq(USER_SYSTEM_ROLE.USER_ID))
        .where(
            USER_SYSTEM_ROLE.ROLE.eq(
                com.loai.inventory.repository.generated.enums.SystemRole.lookupLiteral(
                    SystemRole.ADMIN.name())))
        .and(APP_USER.ACTIVE.isTrue())
        .forUpdate()
        .fetchSet(USER_SYSTEM_ROLE.USER_ID);
  }

  private AppUser toAppUser(AppUserRecord r) {
    AppUser user =
        new AppUser(
            r.getId(),
            r.getEmail(),
            r.getPasswordHash(),
            ActorType.valueOf(r.getActorType().getLiteral()),
            r.getActive(),
            r.getTokenVersion(),
            r.getCreatedAt(),
            r.getUpdatedAt());
    user.setDisplayName(r.getDisplayName());
    return user;
  }

  private UserOrgRole toUserOrgRole(UserOrgRoleRecord r) {
    return new UserOrgRole(r.getUserId(), r.getOrgId(), OrgRole.valueOf(r.getRole().getLiteral()));
  }
}
