package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.PlatformQueueOrg;
import java.util.UUID;

/**
 * The tenant block on every cross-org queue row: {@code {id, name, slug, status}}.
 *
 * <p>{@code status} is the {@link com.loai.inventory.domain.model.OrgStatus} the server derived —
 * never the {@code active} boolean, and never something the client re-derives. Two values cannot
 * express three states, and the platform console's suspended tile drifted exactly that way once
 * ({@code stories/platform_tenant_states.md}).
 *
 * <p>A row whose tenant is {@code suspended} is <em>listed</em>, not hidden: the money a locked-out
 * merchant owes is the money most likely to go unworked. The status is here so the console can say
 * why that row has nobody else minding it.
 */
public record AdminQueueOrgResponse(UUID id, String name, String slug, String status) {

  public static AdminQueueOrgResponse from(PlatformQueueOrg org) {
    return new AdminQueueOrgResponse(org.id(), org.name(), org.slug(), org.status().wire());
  }
}
