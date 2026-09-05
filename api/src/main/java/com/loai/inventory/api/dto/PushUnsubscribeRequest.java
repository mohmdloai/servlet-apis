package com.loai.inventory.api.dto;

/** {@code DELETE /api/me/push-subscriptions} — which endpoint to forget. */
public class PushUnsubscribeRequest {
  private String endpoint;

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }
}
