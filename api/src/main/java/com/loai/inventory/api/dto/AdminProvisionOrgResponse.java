package com.loai.inventory.api.dto;

import com.loai.inventory.service.platform.PlatformOrgService;
import java.util.UUID;

/**
 * Response for {@code POST /api/admin/orgs} (PG1): the newly provisioned org plus its OWNER. {@code
 * owner.minted} tells the console whether the owner account was created fresh (so it can prompt to
 * send a password-reset/invite) or attached from an existing user.
 */
public record AdminProvisionOrgResponse(OrgResponse org, Owner owner) {

  public record Owner(UUID id, String email, boolean minted) {}

  public static AdminProvisionOrgResponse from(PlatformOrgService.ProvisionResult r) {
    return new AdminProvisionOrgResponse(
        OrgResponse.from(r.org()),
        new Owner(r.owner().getId(), r.owner().getEmail(), r.ownerMinted()));
  }
}
