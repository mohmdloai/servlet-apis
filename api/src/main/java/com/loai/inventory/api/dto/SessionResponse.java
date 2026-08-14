package com.loai.inventory.api.dto;

import com.loai.inventory.service.auth.RefreshTokenStore;
import java.time.Instant;
import java.util.UUID;

public class SessionResponse {
  private UUID familyId;
  private String deviceInfo;
  private String sourceIp;
  private Instant lastIssuedAt;

  /**
   * True on the row whose family matches the caller's presented refresh token — the server-truth
   * "this device" marker (a client must not guess; "newest is probably yours" is the wrong standard
   * for a revoke button). A primitive, so it is always on the wire; all-false is the honest state
   * when the presenting token could not be resolved.
   */
  private boolean current;

  public SessionResponse() {}

  public SessionResponse(
      UUID familyId, String deviceInfo, String sourceIp, Instant lastIssuedAt, boolean current) {
    this.familyId = familyId;
    this.deviceInfo = deviceInfo;
    this.sourceIp = sourceIp;
    this.lastIssuedAt = lastIssuedAt;
    this.current = current;
  }

  public static SessionResponse from(RefreshTokenStore.SessionInfo info, boolean current) {
    return new SessionResponse(
        info.familyId(), info.deviceInfo(), info.sourceIp(), info.lastIssuedAt(), current);
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

  public boolean isCurrent() {
    return current;
  }

  public void setCurrent(boolean current) {
    this.current = current;
  }
}
