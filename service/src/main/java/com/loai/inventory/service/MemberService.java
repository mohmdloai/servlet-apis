package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.OrgMember;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import com.loai.inventory.service.auth.AuthService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Org-scoped team management: the roster read plus add / set-role / remove, gated OWNER at the
 * servlet. Enforces the last-owner invariant (an org always keeps ≥1 OWNER) and fires a
 * de-privilege logout-all whenever a change reduces a member's authority, so the org roles baked
 * into their JWT can't outlive the demotion. See {@code stories/09_st_org_settings.md}.
 */
public class MemberService {

  private static final Logger log = LoggerFactory.getLogger(MemberService.class);

  private final DSLContext dsl;
  private final UserRepositoryFactory userRepoFactory;
  private final AuthService authService;

  public MemberService(
      DSLContext dsl, UserRepositoryFactory userRepoFactory, AuthService authService) {
    this.dsl = dsl;
    this.userRepoFactory = userRepoFactory;
    this.authService = authService;
  }

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  /** One page of the org's team plus the distinct-member total. */
  public record MemberPage(List<OrgMember> items, long total) {}

  /** The org's team, each member with their aggregated role set. Email order. */
  public List<OrgMember> listMembers(UUID orgId) {
    return userRepoFactory.create(dsl).findMembers(orgId);
  }

  /**
   * One page of the org's team (email order) plus the total, so the roster tile reads its count as
   * {@code total} instead of loading every row. {@code page} floors at 0, {@code size} clamps to
   * {@code [1, MAX_PAGE_SIZE]}. See {@code stories/org_health_rollup.md}.
   */
  public MemberPage listMembers(UUID orgId, int page, int size) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    UserRepository repo = userRepoFactory.create(dsl);
    return new MemberPage(repo.findMembers(orgId, p * s, s), repo.countMembers(orgId));
  }

  /**
   * Grant {@code role} in {@code orgId} to the existing account with {@code email} (v1
   * attach-existing-only — an unknown email is a 404; full invite-by-email is a follow-up). A grant
   * only increases authority, so it forces no logout — the new org appears on the invitee's next
   * token refresh.
   */
  public OrgMember addMember(UUID orgId, String email, OrgRole role) {
    if (email == null || email.isBlank()) {
      throw new ValidationException("email is required");
    }
    String normalized = Text.normalizeEmail(email);
    UUID grantedId =
        dsl.transactionResult(
            cfg -> {
              UserRepository repo = userRepoFactory.create(DSL.using(cfg));
              AppUser user =
                  repo.findByEmail(normalized)
                      .orElseThrow(
                          () -> new NotFoundException("No user with that email: " + normalized));
              if (user.getActorType() != ActorType.USER) {
                throw new ValidationException("Only USER accounts can be org members");
              }
              if (!repo.findRolesInOrg(user.getId(), orgId).isEmpty()) {
                throw new ConflictException(
                    "User is already a member of this org; edit their role instead");
              }
              repo.insertOrgRole(user.getId(), orgId, role);
              return user.getId();
            });
    log.info("Org {} added member {} as {}", orgId, grantedId, role);
    return readMember(orgId, grantedId);
  }

  /**
   * Set {@code role} as the member's sole role in {@code orgId} (set-replaces). 404 if the user is
   * not already a member. A demotion (new rank below the member's current highest) trips the
   * last-owner guard when it drops an OWNER, and fires a de-privilege logout-all.
   */
  public OrgMember setRole(UUID orgId, UUID userId, OrgRole role) {
    Integer newVersion =
        dsl.transactionResult(
            cfg -> {
              UserRepository repo = userRepoFactory.create(DSL.using(cfg));
              Set<OrgRole> current = repo.findRolesInOrg(userId, orgId);
              if (current.isEmpty()) {
                throw new NotFoundException("User is not a member of this org");
              }
              boolean reducesAuthority = rank(role) < maxRank(current);
              if (current.contains(OrgRole.OWNER) && role != OrgRole.OWNER) {
                guardLastOwner(repo, orgId, userId);
              }
              repo.deleteAllOrgRoles(userId, orgId);
              repo.insertOrgRole(userId, orgId, role);
              return reducesAuthority ? repo.incrementTokenVersion(userId) : null;
            });
    if (newVersion != null) {
      authService.propagateLogoutAll(userId, newVersion);
    }
    log.info("Org {} set member {} role to {}", orgId, userId, role);
    return readMember(orgId, userId);
  }

  /**
   * Remove {@code userId} from {@code orgId} entirely (all roles). 404 if not a member; trips the
   * last-owner guard when the target is an OWNER. Removal always reduces authority, so it fires a
   * de-privilege logout-all.
   */
  public void removeMember(UUID orgId, UUID userId) {
    int newVersion =
        dsl.transactionResult(
            cfg -> {
              UserRepository repo = userRepoFactory.create(DSL.using(cfg));
              Set<OrgRole> current = repo.findRolesInOrg(userId, orgId);
              if (current.isEmpty()) {
                throw new NotFoundException("User is not a member of this org");
              }
              if (current.contains(OrgRole.OWNER)) {
                guardLastOwner(repo, orgId, userId);
              }
              repo.deleteAllOrgRoles(userId, orgId);
              return repo.incrementTokenVersion(userId);
            });
    authService.propagateLogoutAll(userId, newVersion);
    log.info("Org {} removed member {}", orgId, userId);
  }

  /**
   * Block a change that would leave {@code orgId} with no OWNER other than {@code targetUserId}.
   */
  private void guardLastOwner(UserRepository repo, UUID orgId, UUID targetUserId) {
    Set<UUID> owners = new HashSet<>(repo.ownerIdsForUpdate(orgId));
    owners.remove(targetUserId);
    if (owners.isEmpty()) {
      throw new ConflictException("Org must have at least one owner");
    }
  }

  private OrgMember readMember(UUID orgId, UUID userId) {
    UserRepository repo = userRepoFactory.create(dsl);
    AppUser u = repo.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
    return new OrgMember(
        u.getId(),
        u.getEmail(),
        u.getDisplayName(),
        repo.findRolesInOrg(userId, orgId),
        u.isActive(),
        u.getCreatedAt());
  }

  private static int rank(OrgRole r) {
    return switch (r) {
      case VIEWER -> 1;
      case STAFF -> 2;
      case MANAGER -> 3;
      case OWNER -> 4;
    };
  }

  private static int maxRank(Set<OrgRole> roles) {
    return roles.stream().mapToInt(MemberService::rank).max().orElse(0);
  }
}
