package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public class AppUser {
  private UUID id;
  private String email;
  private String displayName;
  private String passwordHash;
  private ActorType actorType;
  private boolean active;
  private int tokenVersion;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public AppUser() {}

  public AppUser(
      UUID id,
      String email,
      String passwordHash,
      ActorType actorType,
      boolean active,
      int tokenVersion,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.email = email;
    this.passwordHash = passwordHash;
    this.actorType = actorType;
    this.active = active;
    this.tokenVersion = tokenVersion;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public ActorType getActorType() {
    return actorType;
  }

  public void setActorType(ActorType actorType) {
    this.actorType = actorType;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public int getTokenVersion() {
    return tokenVersion;
  }

  public void setTokenVersion(int tokenVersion) {
    this.tokenVersion = tokenVersion;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(OffsetDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  @Override
  public String toString() {
    return "AppUser{id=" + id + ", email='" + email + "', actorType=" + actorType + "}";
  }
}
