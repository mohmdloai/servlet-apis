package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * One member of an org: the {@code app_user} identity joined to the set of {@link OrgRole}s the
 * user holds in that org (a user may hold more than one — {@code user_org_role} is keyed on all
 * three columns). Read projection backing {@code GET /api/orgs/{orgId}/members} (stories/09).
 */
public record OrgMember(
    UUID userId,
    String email,
    String displayName,
    Set<OrgRole> roles,
    boolean active,
    OffsetDateTime createdAt) {}
