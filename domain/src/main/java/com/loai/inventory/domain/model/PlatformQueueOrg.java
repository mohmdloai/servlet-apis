package com.loai.inventory.domain.model;

import java.util.UUID;

/**
 * The tenant a {@link PlatformQueueRow} belongs to — the whole reason a cross-org queue is worth
 * reading. Carried on every row of every kind.
 *
 * <p>{@code status} is the {@link OrgStatus} the server derived, never a boolean and never a client
 * re-derivation: two values cannot express three states, and the platform console's suspended tile
 * already drifted once that way ({@code stories/platform_tenant_states.md}).
 *
 * <p><strong>Queues span suspended tenants.</strong> Nothing filters on this field — a suspended
 * merchant still owes real customers real money, and being locked out means nobody on the org side
 * is working that queue. Those rows are <em>more</em> urgent, not less; the status is here so an
 * operator can see why a row has no one else minding it.
 */
public record PlatformQueueOrg(UUID id, String name, String slug, OrgStatus status) {}
