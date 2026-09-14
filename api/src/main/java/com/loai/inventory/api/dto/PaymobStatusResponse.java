package com.loai.inventory.api.dto;

import com.loai.inventory.service.OrgPaymobService;

/**
 * The merchant's Paymob connection, as a settings screen sees it.
 *
 * <p>There is deliberately <b>no secret field of any kind</b> — not {@code secret_key}, not {@code
 * hmac_secret}, not a masked prefix. {@code public_key} is the one credential meant to leave the
 * server: the shopper's browser needs it to open Unified Checkout. Nulls are omitted, so a
 * never-connected org answers with just {@code {"connected": false}}.
 */
public class PaymobStatusResponse {
  private boolean connected;
  private String status;
  private String publicKey;
  private Integer cardIntegrationId;
  private String region;
  private java.time.OffsetDateTime connectedAt;
  private java.time.OffsetDateTime updatedAt;

  public static PaymobStatusResponse from(OrgPaymobService.ConnectionStatus s) {
    PaymobStatusResponse r = new PaymobStatusResponse();
    r.connected = s.connected();
    r.status = s.status() == null ? null : s.status().name();
    r.publicKey = s.publicKey();
    r.cardIntegrationId = s.cardIntegrationId();
    r.region = s.region();
    r.connectedAt = s.connectedAt();
    r.updatedAt = s.updatedAt();
    return r;
  }

  public boolean isConnected() {
    return connected;
  }

  public String getStatus() {
    return status;
  }

  public String getPublicKey() {
    return publicKey;
  }

  public Integer getCardIntegrationId() {
    return cardIntegrationId;
  }

  public String getRegion() {
    return region;
  }

  public java.time.OffsetDateTime getConnectedAt() {
    return connectedAt;
  }

  public java.time.OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
