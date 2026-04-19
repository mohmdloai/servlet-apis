package com.loai.inventory.api.dto;

import com.loai.inventory.service.auth.RefreshTokenStore;
import java.time.Instant;
import java.util.UUID;

public class SessionResponse {
  private UUID familyId;
  private String deviceInfo;
  private String sourceIp;
  private Instant lastIssuedAt;

  public SessionResponse() {}

  public SessionResponse(UUID familyId, String deviceInfo, String sourceIp, Instant lastIssuedAt) {
    this.familyId = familyId;
    this.deviceInfo = deviceInfo;
    this.sourceIp = sourceIp;
    this.lastIssuedAt = lastIssuedAt;
  }

  public static SessionResponse from(RefreshTokenStore.SessionInfo info) {
    return new SessionResponse(
        info.familyId(), info.deviceInfo(), info.sourceIp(), info.lastIssuedAt());
  }

  public UUID getFamilyId() {
    return familyId;
  }

  public void setFamilyId(UUID familyId) {
    this.familyId = familyId;
  }

  public String getDeviceInfo() {
    return deviceInfo;
  }

  public void setDeviceInfo(String deviceInfo) {
    this.deviceInfo = deviceInfo;
  }

  public String getSourceIp() {
    return sourceIp;
  }

  public void setSourceIp(String sourceIp) {
    this.sourceIp = sourceIp;
  }

  public Instant getLastIssuedAt() {
    return lastIssuedAt;
  }

  public void setLastIssuedAt(Instant lastIssuedAt) {
    this.lastIssuedAt = lastIssuedAt;
  }
}
