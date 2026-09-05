package com.loai.inventory.api.dto;

/**
 * {@code GET /api/me/push/config} — whether this server can send Web Push at all, and the VAPID
 * public key the browser subscribes with. {@code enabled} is a primitive (always on the wire);
 * {@code public_key} is present iff enabled. Served at runtime so a key rotation needs no frontend
 * rebuild and no {@code NEXT_PUBLIC_*} build-arg.
 */
public class PushConfigResponse {
  private boolean enabled;
  private String publicKey;

  public static PushConfigResponse of(boolean enabled, String publicKey) {
    PushConfigResponse r = new PushConfigResponse();
    r.enabled = enabled;
    r.publicKey = enabled ? publicKey : null;
    return r;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getPublicKey() {
    return publicKey;
  }
}
