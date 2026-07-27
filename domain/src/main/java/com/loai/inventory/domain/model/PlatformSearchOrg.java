package com.loai.inventory.domain.model;

import java.util.UUID;

/**
 * The tenant a {@link PlatformSearchResult} belongs to — and, for a {@code customer} result, the
 * whole answer. "This email shops at Nour Store and at Rehab Mart" <em>is</em> the reply to the
 * support question that started the search.
 *
 * <p>{@code status} is the {@link OrgStatus} the server derived, never a boolean and never a client
 * re-derivation: two values cannot express three states, and the platform console's suspended tile
 * already drifted once that way ({@code stories/platform_tenant_states.md}). The derivation itself
 * is not restated here — {@link OrgStatus#of} is the one definition.
 *
 * <p><strong>Deliberately a separate record from {@link PlatformQueueOrg}, which has the same four
 * fields.</strong> Each cross-org read declares its own field whitelist (rule 3 of {@link
 * com.loai.inventory.domain.repository.PlatformSearchRepository}); sharing one tenant record
 * between two independent whitelists would mean a field added for a queue row silently widens every
 * search result too. The rule that genuinely must not be written twice — how a status is derived —
 * is shared, and it is.
 *
 * <p><strong>Suspended tenants are searchable.</strong> Nothing filters on this field. A suspended
 * merchant's orders are the ones most likely to generate support contact, and the badge on the row
 * tells the operator why nobody else is minding them. Same rule, same reason as the queues.
 */
public record PlatformSearchOrg(UUID id, String name, String slug, OrgStatus status) {}
