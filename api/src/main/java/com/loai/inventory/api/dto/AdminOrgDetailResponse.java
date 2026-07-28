package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.OrgHealth;
import com.loai.inventory.domain.model.OrgMember;
import com.loai.inventory.domain.model.OrgStatus;
import com.loai.inventory.service.platform.PlatformOrgService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * An org plus its operational rollup, for the platform drill-down view.
 *
 * <p>{@code status} is named here for the same reason it is named on {@link
 * AdminOrgSummaryResponse}: the client renders the state, it never re-derives it from the embedded
 * {@code org.active}.
 *
 * <p>{@code suspendedAt} / {@code suspendedReason} are non-null only when {@code status ==
 * suspended} (Jackson omits nulls, so the keys are simply absent otherwise), which is what lets the
 * detail page say <em>why</em> and <em>since when</em>. {@code POST .../suspend {reason}} has
 * written that note since the lifecycle slice shipped and nothing has ever read it back.
 *
 * <p><b>Admin plane only.</b> These two fields deliberately do not go on the shared {@link
 * OrgResponse}: the tenant-facing {@code GET /api/orgs/{orgId}} has no business carrying the
 * platform's internal suspension note, and members of a suspended org are 403'd by {@code
 * requireOrgAccess} anyway. The note stays private to the plane that wrote it.
 */
public record AdminOrgDetailResponse(
    OrgResponse org,
    String status,
    OffsetDateTime suspendedAt,
    String suspendedReason,
    Health health,
    List<Owner> owners) {

  /** The org-scoped aggregates a platform operator triages against. */
  public record Health(
      long memberCount, long pendingPaymentOrders, long openDisputes, long unallocatedPayments) {

    public static Health from(OrgHealth h) {
      return new Health(
          h.memberCount(), h.pendingPaymentOrders(), h.openDisputes(), h.unallocatedPayments());
    }
  }

  /**
   * One member holding OWNER — <b>who to act on</b>, which is what this page could not answer.
   * Before this, the detail carried four health counts and no identity at all, so an operator
   * looking at a PENDING tenant knew <em>that</em> it was waiting on its owner's email and had no
   * way to find out <em>whose</em>; {@code memberCount: 1} was as close as it got.
   *
   * <p>{@code emailVerified} is the field the page acts on, and it is a primitive for the reason
   * spelled out on {@link AdminUserResponse}: on this surface its {@code false} is the whole
   * diagnosis, so it must never be expressible as an absent key.
   */
  public record Owner(
      UUID id, String email, String displayName, boolean active, boolean emailVerified) {

    static Owner from(OrgMember m) {
      return new Owner(
          m.userId(), m.email(), m.displayName(), m.active(), m.emailVerifiedAt() != null);
    }
  }

  public static AdminOrgDetailResponse from(PlatformOrgService.OrgWithHealth v) {
    return new AdminOrgDetailResponse(
        OrgResponse.from(v.org()),
        OrgStatus.of(v.org()).wire(),
        v.org().getSuspendedAt(),
        v.org().getSuspendedReason(),
        Health.from(v.health()),
        v.owners().stream().map(Owner::from).toList());
  }
}
