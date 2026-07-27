package com.loai.inventory.api.dto;

import com.loai.inventory.service.platform.UserAdminService;
import java.time.OffsetDateTime;

/**
 * A confirmed resend: the address the link actually went to, and when it stops redeeming.
 *
 * <p>The address is echoed because "sent" on its own does not let an operator confirm they rescued
 * the right person — they are usually on the phone with someone at the time. This body is only ever
 * written on a genuine 2xx; a delivery failure is a 502, never this shape with a hopeful message.
 */
public record ResendVerificationResponse(String email, OffsetDateTime expiresAt) {

  public static ResendVerificationResponse from(UserAdminService.ResendResult r) {
    return new ResendVerificationResponse(r.email(), r.expiresAt());
  }
}
