package com.loai.inventory.domain.model;

/**
 * The tenant lifecycle funnel's six stages, in the order the product presents them (slice 7 of the
 * platform console, {@code stories/platform_tenant_funnel.md}). This ordering is the
 * <strong>only</strong> place the sequence is defined — {@code org_milestone.milestone} is open
 * text with no CHECK constraint, so a milestone this enum does not name is stored (a future stage
 * needs no migration to be recorded) and simply not rendered.
 *
 * <p><strong>The stages are not a chain.</strong> A tenant is counted at stage N iff a row exists
 * for that stage — never "N implies N-1", never "N-1 implies N". An {@code IN_STORE}-only merchant
 * reaches {@link #FIRST_ORDER} having never reached {@link #PUBLISHED} (no storefront needed to
 * sell at the counter), so a later stage's count can legitimately exceed an earlier one's. Nothing
 * in this codebase may clamp, sort, or "repair" that — it is a finding, not a rendering bug.
 */
public enum PlatformFunnelStage {
  /** Org row created — both acquisition paths (self-serve registration, platform provisioning). */
  REGISTERED,

  /**
   * Self-serve: the owner verifies their email. Provisioned: provisioning commits — {@code
   * PlatformOrgService} sets {@code org.active = true} at creation, so a provisioned tenant is born
   * activated and skips this stage's self-serve meaning entirely.
   */
  ACTIVATED,

  /** First {@code product_listing} row ever created for the org, at any status. */
  CATALOGUED,

  /**
   * First listing published. <strong>Lossy on backfill</strong>: {@code
   * ProductListingService.publish} sets {@code published_at}; {@code unpublish} sets it back to
   * {@code null}, so a tenant that unpublished everything before this migration ran has no durable
   * trace of its first launch and is left unstamped rather than guessed. Going forward the write
   * site closes the gap for good.
   */
  PUBLISHED,

  /**
   * First {@code sales_order} placed, any channel (including {@code IN_STORE}, no listing needed).
   */
  FIRST_ORDER,

  /** First {@code payment} row recorded against the org, at any reconciliation outcome. */
  FIRST_PAYMENT
}
