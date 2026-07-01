package com.loai.inventory.api.dto;

import com.loai.inventory.service.auth.AuthService;
import java.util.UUID;

/**
 * Response for impersonation start/stop. {@code read_only} is a boxed Boolean so a stop response
 * (all nulls) serializes to {@code {}} under the null-omitting ObjectMapper.
 */
public record ImpersonationResponse(UUID impersonatorId, String tier, Boolean readOnly) {

  public static ImpersonationResponse started(AuthService.ImpersonationResult r) {
    return new ImpersonationResponse(r.impersonatorId(), r.tier().name(), r.readOnly());
  }

  public static ImpersonationResponse stopped() {
    return new ImpersonationResponse(null, null, null);
  }
}
