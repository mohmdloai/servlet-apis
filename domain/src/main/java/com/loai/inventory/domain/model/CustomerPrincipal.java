package com.loai.inventory.domain.model;

import java.util.UUID;

/**
 * The authenticated principal on the customer portal plane ({@code /api/portal/*}), built by {@code
 * CustomerAuthFilter} from a verified {@code customer_access} token. Deliberately <em>not</em> a
 * {@link SecurityContext}: a customer carries no org/system roles and no impersonation overlay —
 * the two planes share nothing forgeable (epic §"two-plane security model"). Handlers read {@code
 * (orgId, customerId)} from here, <em>never</em> from the URL or body, so cross-customer /
 * cross-org reach is unrepresentable.
 *
 * @param customerId the CRM {@code customer} row this session belongs to ({@code sub})
 * @param orgId the store the customer authenticated to ({@code org_id}) — every portal read is
 *     scoped to it
 * @param tokenVersion the {@code token_version} the access token was minted at (fail-closed
 *     validated against the Redis mirror)
 * @param familyId the refresh-token family = one device (the {@code fam} claim), for the per-device
 *     access kill-switch
 */
public record CustomerPrincipal(UUID customerId, UUID orgId, int tokenVersion, UUID familyId) {}
