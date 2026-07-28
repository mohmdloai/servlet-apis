package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.AppUser;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A user row in the platform user list. Never carries the password hash.
 *
 * <p><b>{@code emailVerified} and {@code emailVerifiedAt} are both sent, and that is not
 * redundancy.</b> Jackson omits nulls, so a bare timestamp would make <em>absence</em> mean "never
 * verified" — and everywhere else in this codebase a missing key means "no news" ({@code
 * degraded[]}, empty search groups, {@code suspendedReason}). Inverting that polarity for the one
 * field whose absence <em>is</em> the incident is how a console lies quietly: a client that dropped
 * the key, or one that branches on {@code !emailVerifiedAt}, would read the alarming state as
 * nothing-to-say and send an operator off to reset a password that was never the problem. The
 * boolean is a primitive, so it is always on the wire and carries the fact; the timestamp is the
 * detail and is absent until there is one. Pinned by {@code
 * PlatformResendVerificationIT.emailVerifiedIsPresentAndFalse_onUnverifiedUser}.
 */
public record AdminUserResponse(
    UUID id,
    String email,
    String actorType,
    boolean active,
    boolean emailVerified,
    OffsetDateTime emailVerifiedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

  public static AdminUserResponse from(AppUser u) {
    return new AdminUserResponse(
        u.getId(),
        u.getEmail(),
        u.getActorType().name(),
        u.isActive(),
        u.getEmailVerifiedAt() != null,
        u.getEmailVerifiedAt(),
        u.getCreatedAt(),
        u.getUpdatedAt());
  }
}
