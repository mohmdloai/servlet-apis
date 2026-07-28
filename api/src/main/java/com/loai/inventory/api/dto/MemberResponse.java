package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.OrgMember;
import com.loai.inventory.domain.model.OrgRole;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One row of the org members roster ({@code GET /api/orgs/{orgId}/members}). {@code roles} is the
 * full set the user holds in the org (usually one); the client renders the highest as the primary
 * chip. {@code createdAt} is the account's creation time (there is no membership-join column).
 *
 * <p>{@code emailVerified} / {@code emailVerifiedAt} ride along from the shared {@link OrgMember}
 * projection, with the same both-fields polarity as {@link AdminUserResponse}. This read is already
 * MANAGER-gated and already carries each member's email and active flag, so "has this colleague
 * verified" is neither new information nor a new audience — and adding it here rather than forking
 * a second projection for the platform console is what keeps the two from drifting.
 */
public record MemberResponse(
    UUID userId,
    String email,
    String displayName,
    List<String> roles,
    boolean active,
    boolean emailVerified,
    OffsetDateTime emailVerifiedAt,
    OffsetDateTime createdAt) {

  public static MemberResponse from(OrgMember m) {
    return new MemberResponse(
        m.userId(),
        m.email(),
        m.displayName(),
        m.roles().stream().map(OrgRole::name).sorted().toList(),
        m.active(),
        m.emailVerifiedAt() != null,
        m.emailVerifiedAt(),
        m.createdAt());
  }
}
