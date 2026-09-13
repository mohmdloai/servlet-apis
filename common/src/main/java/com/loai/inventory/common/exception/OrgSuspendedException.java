package com.loai.inventory.common.exception;

/**
 * A member of a suspended org reached an org route ({@code stories/support_ticket_reach.md}). The
 * same 403 {@code AuthzHelper.requireOrgAccess} always threw, now carrying {@link #KIND} so the org
 * app can lead the member to the one door that stays open — support — instead of failing on every
 * screen. Only ever thrown <em>after</em> the membership check: an outsider still gets the generic
 * "No access", so suspension cannot be enumerated from outside.
 */
public class OrgSuspendedException extends AuthorizationException {

  public static final String KIND = "ORG_SUSPENDED";

  public OrgSuspendedException() {
    super("Org suspended");
  }
}
