package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.ORG;

import com.loai.inventory.domain.model.OrgStatus;
import org.jooq.Condition;
import org.jooq.impl.DSL;

/**
 * The single place {@link OrgStatus}'s rule becomes SQL. Built mechanically from the enum's own
 * constants, so the org list's {@code ?status=} filter ({@link OrgRepositoryImpl}) and the platform
 * overview's tenant census ({@link PlatformStatsRepositoryImpl}) run byte-identical predicates and
 * cannot drift — which is exactly how the suspended tile and its drill-down disagreed before {@code
 * stories/platform_tenant_states.md}.
 *
 * <p>Do not hand-write {@code ORG.ACTIVE.eq(...)} for a status anywhere else.
 */
final class OrgStatusConditions {

  private OrgStatusConditions() {}

  /** The predicate selecting exactly the orgs in {@code status}; {@code null} selects all. */
  static Condition matching(OrgStatus status) {
    if (status == null) {
      return DSL.noCondition();
    }
    Condition condition = ORG.ACTIVE.eq(status.activeFlag());
    Boolean suspendedAtIsNull = status.suspendedAtIsNull();
    if (suspendedAtIsNull == null) {
      return condition;
    }
    return condition.and(
        suspendedAtIsNull ? ORG.SUSPENDED_AT.isNull() : ORG.SUSPENDED_AT.isNotNull());
  }
}
