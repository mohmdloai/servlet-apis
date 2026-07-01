package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.AppUser;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A user row in the platform user list. Never carries the password hash. */
public record AdminUserResponse(
    UUID id,
    String email,
    String actorType,
    boolean active,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

  public static AdminUserResponse from(AppUser u) {
    return new AdminUserResponse(
        u.getId(),
        u.getEmail(),
        u.getActorType().name(),
        u.isActive(),
        u.getCreatedAt(),
        u.getUpdatedAt());
  }
}
