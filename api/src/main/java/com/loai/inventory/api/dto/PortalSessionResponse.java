package com.loai.inventory.api.dto;

import com.loai.inventory.service.auth.CustomerSessionStore;
import java.time.Instant;
import java.util.UUID;

/** One active portal device (family) for {@code GET /api/portal/auth/sessions}. */
public class PortalSessionResponse {
  private UUID familyId;
  private String deviceInfo;
  private String sourceIp;
  private Instant lastIssuedAt;

  public PortalSessionResponse() {}

  public PortalSessionResponse(
      UUID familyId, String deviceInfo, String sourceIp, Instant lastIssuedAt) {
    this.familyId = familyId;
    this.deviceInfo = deviceInfo;
    this.sourceIp = sourceIp;
    this.lastIssuedAt = lastIssuedAt;
  }

  public static PortalSessionResponse from(CustomerSessionStore.SessionInfo info) {
    return new PortalSessionResponse(
        info.familyId(), info.deviceInfo(), info.sourceIp(), info.lastIssuedAt());
  }

  public UUID getFamilyId() {
    return familyId;
  }

  public String getDeviceInfo() {
    return deviceInfo;
  }

  public String getSourceIp() {
    return sourceIp;
  }

  public Instant getLastIssuedAt() {
    return lastIssuedAt;
  }
}
