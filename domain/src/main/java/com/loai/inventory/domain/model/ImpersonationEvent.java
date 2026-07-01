package com.loai.inventory.domain.model;

import java.util.UUID;

/**
 * One audited START/STOP of an impersonation overlay (Layer-1 audit; see {@code
 * docs/impersonation.md}). {@code createdAt} is assigned by the database default, so it is not part
 * of the insert contract.
 *
 * @param impersonatorId the real driver (the admin/owner who started or stopped the overlay)
 * @param targetId the user being impersonated
 * @param tier PLATFORM or ORG
 * @param scopeOrgId the confined org for an ORG-tier event; {@code null} for PLATFORM
 * @param event START or STOP
 * @param reason optional free-text justification
 * @param sourceIp request source IP, when known
 * @param userAgent request User-Agent, when known
 */
public record ImpersonationEvent(
    UUID impersonatorId,
    UUID targetId,
    ImpersonationTier tier,
    UUID scopeOrgId,
    Event event,
    String reason,
    String sourceIp,
    String userAgent) {

  public enum Event {
    START,
    STOP
  }

  public static ImpersonationEvent start(
      UUID impersonatorId,
      UUID targetId,
      ImpersonationTier tier,
      UUID scopeOrgId,
      String reason,
      Environment env) {
    return new ImpersonationEvent(
        impersonatorId,
        targetId,
        tier,
        scopeOrgId,
        Event.START,
        reason,
        env == null ? null : env.sourceIp(),
        env == null ? null : env.userAgent());
  }

  public static ImpersonationEvent stop(
      UUID impersonatorId,
      UUID targetId,
      ImpersonationTier tier,
      UUID scopeOrgId,
      Environment env) {
    return new ImpersonationEvent(
        impersonatorId,
        targetId,
        tier,
        scopeOrgId,
        Event.STOP,
        null,
        env == null ? null : env.sourceIp(),
        env == null ? null : env.userAgent());
  }
}
