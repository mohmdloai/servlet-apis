package com.loai.inventory.api.dto;

/**
 * {@code POST /api/orgs/{orgId}/whatsapp} — the merchant handing over the WhatsApp Business Account
 * they connected in Meta's WhatsApp Manager. The {@code access_token} is write-only: it is sealed
 * immediately and no response ever echoes it back.
 */
public class WhatsAppConnectRequest {
  private String wabaId;
  private String phoneNumberId;
  private String displayPhoneNumber;
  private String accessToken;

  public String getWabaId() {
    return wabaId;
  }

  public void setWabaId(String wabaId) {
    this.wabaId = wabaId;
  }

  public String getPhoneNumberId() {
    return phoneNumberId;
  }

  public void setPhoneNumberId(String phoneNumberId) {
    this.phoneNumberId = phoneNumberId;
  }

  public String getDisplayPhoneNumber() {
    return displayPhoneNumber;
  }

  public void setDisplayPhoneNumber(String displayPhoneNumber) {
    this.displayPhoneNumber = displayPhoneNumber;
  }

  public String getAccessToken() {
    return accessToken;
  }

  public void setAccessToken(String accessToken) {
    this.accessToken = accessToken;
  }
}
