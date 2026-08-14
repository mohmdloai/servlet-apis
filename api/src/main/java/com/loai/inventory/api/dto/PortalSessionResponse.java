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

  /**
   * Same contract as {@code SessionResponse#current} — server-truth "this device", never a guess.
   */
  private boolean current;

  public PortalSessionResponse() {}

  public PortalSessionResponse(
      UUID familyId, String deviceInfo, String sourceIp, Instant lastIssuedAt, boolean current) {
    this.familyId = familyId;
    this.deviceInfo = deviceInfo;
    this.sourceIp = sourceIp;
    this.lastIssuedAt = lastIssuedAt;
    this.current = current;
  }

  public static PortalSessionResponse from(CustomerSessionStore.SessionInfo info, boolean current) {
    return new PortalSessionResponse(
        info.familyId(), info.deviceInfo(), info.sourceIp(), info.lastIssuedAt(), current);
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

  public boolean isCurrent() {
    return current;
  }

  public void setCurrent(boolean current) {
    this.current = current;
  }
}
