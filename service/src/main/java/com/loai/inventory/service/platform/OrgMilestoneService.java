package com.loai.inventory.service.platform;

import com.loai.inventory.domain.model.PlatformFunnelStage;
import com.loai.inventory.domain.repository.OrgMilestoneRepositoryFactory;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The one helper every milestone write goes through (slice 7, {@code
 * stories/platform_tenant_funnel.md}) — six call sites across {@code AccountService}, {@code
 * PlatformOrgService}, {@code ProductListingService}, {@code SalesOrderService} and {@code
 * PaymentService}, one definition of how a stage gets stamped.
 *
 * <p><strong>Always called inside the caller's own business transaction, never after it.</strong> A
 * milestone written outside its txn is a milestone that can disagree with the thing it records — if
 * the order placement rolls back, the {@code FIRST_ORDER} stamp must roll back with it. This class
 * therefore never opens a transaction of its own; every call takes the caller's {@link DSLContext}.
 *
 * <p><strong>Never best-effort.</strong> {@link #reach} swallows nothing and returns nothing to
 * ignore — a failed insert propagates like any other write in the transaction. A silently-dropped
 * milestone is a funnel that under-reports forever with no way to notice, which is worse than
 * failing the action that would have recorded it.
 *
 * <p>Idempotence is not this class's job — it belongs to the {@code (org_id, milestone)} PRIMARY
 * KEY via {@code ON CONFLICT DO NOTHING} in {@link
 * com.loai.inventory.domain.repository.OrgMilestoneRepository}. Publish, unpublish, republish all
 * call {@link #reach} with {@link PlatformFunnelStage#PUBLISHED}; only the first one ever writes a
 * row.
 */
public class OrgMilestoneService {

  private final OrgMilestoneRepositoryFactory repoFactory;

  public OrgMilestoneService(OrgMilestoneRepositoryFactory repoFactory) {
    this.repoFactory = repoFactory;
  }

  /**
   * Stamp {@code stage} reached for {@code orgId} at {@code at}, inside {@code ctx}'s transaction.
   */
  public void reach(DSLContext ctx, UUID orgId, PlatformFunnelStage stage, OffsetDateTime at) {
    repoFactory.create(ctx).insertIfAbsent(orgId, stage.name(), at);
  }
}
