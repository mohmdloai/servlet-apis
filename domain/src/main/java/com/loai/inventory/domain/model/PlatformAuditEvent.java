package com.loai.inventory.domain.model;

import java.util.UUID;

/**
 * One audited mutating platform-tier action (see {@code docs/platform-admin-plan.md}, slice 0). The
 * shared accountability ledger behind org suspend/reactivate, user create/disable, role
 * grant/revoke, and cross-user force-logout. {@code createdAt} is assigned by the database default,
 * so it is not part of the insert contract.
 *
 * @param actorId the platform admin who performed the action
 * @param action a stable verb, e.g. {@code ORG_SUSPEND}, {@code ROLE_GRANT}, {@code FORCE_LOGOUT}
 * @param targetType the kind of entity acted on: {@code ORG}, {@code USER}, {@code SESSION}
 * @param targetId the acted-on entity id, when there is a single one; {@code null} otherwise
 * @param detailJson action-specific payload, already serialised to JSON; {@code null} when empty
 * @param sourceIp request source IP, when known
 * @param userAgent request User-Agent, when known
 */
public record PlatformAuditEvent(
    UUID actorId,
    String action,
    String targetType,
    UUID targetId,
    String detailJson,
    String sourceIp,
    String userAgent) {

  /** Common target kinds - kept as constants so callers agree on the vocabulary. */
  public static final class Target {
    public static final String ORG = "ORG";
    public static final String USER = "USER";
    public static final String SESSION = "SESSION";

    private Target() {}
  }
}
