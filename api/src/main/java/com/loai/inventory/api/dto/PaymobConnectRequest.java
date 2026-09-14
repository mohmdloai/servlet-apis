package com.loai.inventory.api.dto;

/**
 * {@code POST /api/orgs/{orgId}/paymob} — the merchant handing over the Paymob credentials from
 * their Paymob dashboard. {@code secret_key} and {@code hmac_secret} are write-only: both are
 * sealed immediately and no response ever echoes either back.
 */
public class PaymobConnectRequest {
  private String publicKey;
  private String secretKey;
  private String hmacSecret;
  private String apiKey;
  private Integer cardIntegrationId;
  private String region;

  public String getPublicKey() {
    return publicKey;
  }

  public void setPublicKey(String publicKey) {
    this.publicKey = publicKey;
  }

  public String getSecretKey() {
    return secretKey;
  }

  public void setSecretKey(String secretKey) {
    this.secretKey = secretKey;
  }

  public String getApiKey() {
    return apiKey;
  }

  public String getHmacSecret() {
    return hmacSecret;
  }

  public void setHmacSecret(String hmacSecret) {
    this.hmacSecret = hmacSecret;
  }

  public Integer getCardIntegrationId() {
    return cardIntegrationId;
  }

  public void setCardIntegrationId(Integer cardIntegrationId) {
    this.cardIntegrationId = cardIntegrationId;
  }

  public String getRegion() {
    return region;
  }

  public void setRegion(String region) {
    this.region = region;
  }
}
