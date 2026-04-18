package com.loai.inventory.api.dto;

import java.util.UUID;

public class AuthResponse {
  private long expiresIn;
  private UUID userId;
  private String actorType;

  public AuthResponse() {}

  public AuthResponse(long expiresIn, UUID userId, String actorType) {
    this.expiresIn = expiresIn;
    this.userId = userId;
    this.actorType = actorType;
  }

  public long getExpiresIn() {
    return expiresIn;
  }

  public void setExpiresIn(long expiresIn) {
    this.expiresIn = expiresIn;
  }

  public UUID getUserId() {
    return userId;
  }

  public void setUserId(UUID userId) {
    this.userId = userId;
  }

  public String getActorType() {
    return actorType;
  }

  public void setActorType(String actorType) {
    this.actorType = actorType;
  }
}
