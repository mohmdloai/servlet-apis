package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * One member of an org: the {@code app_user} identity joined to the set of {@link OrgRole}s the
 * user holds in that org (a user may hold more than one — {@code user_org_role} is keyed on all
 * three columns). Read projection backing {@code GET /api/orgs/{orgId}/members} (stories/09).
 *
 * <p>{@code emailVerifiedAt} is null for an account that has never redeemed its verification link —
 * which, for the sole OWNER of a {@link OrgStatus#PENDING} org, is the entire reason that tenant is
 * stuck ({@code AccountService.verifyEmail} is what calls {@code activateRegistrationPendingOrgs}).
 * Added to <b>this</b> projection rather than a second one so the org-plane roster and the platform
 * console's {@code owners} block read one definition: the roster is already MANAGER-gated and
 * already exposes each member's email and active flag, so "has this colleague verified" is neither
 * new information nor a new audience.
 */
public record OrgMember(
    UUID userId,
    String email,
    String displayName,
    Set<OrgRole> roles,
    boolean active,
    OffsetDateTime createdAt,
    OffsetDateTime emailVerifiedAt) {}
