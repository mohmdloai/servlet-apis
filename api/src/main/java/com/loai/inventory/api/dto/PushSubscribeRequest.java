package com.loai.inventory.api.dto;

/**
 * {@code POST /api/me/push-subscriptions} — the browser's {@code PushSubscription.toJSON()} shape:
 * {@code {endpoint, keys: {p256dh, auth}}}, plus the optional {@code user_agent} the client labels
 * the device with. The three subscription fields are a bearer capability to message this device and
 * are never echoed back by any read.
 */
public class PushSubscribeRequest {
  private String endpoint;
  private Keys keys;
  private String userAgent;

  public static class Keys {
    private String p256dh;
    private String auth;

    public String getP256dh() {
      return p256dh;
    }

    public void setP256dh(String p256dh) {
      this.p256dh = p256dh;
    }

    public String getAuth() {
      return auth;
    }

    public void setAuth(String auth) {
      this.auth = auth;
    }
  }

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }

  public Keys getKeys() {
    return keys;
  }

  public void setKeys(Keys keys) {
    this.keys = keys;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public void setUserAgent(String userAgent) {
    this.userAgent = userAgent;
  }
}
