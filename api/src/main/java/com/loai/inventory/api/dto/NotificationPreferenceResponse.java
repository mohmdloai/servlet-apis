package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.NotificationPreference;

/**
 * One notification-preference row (read projection). {@code channel} is the DB literal ({@code
 * in_app}/{@code email}); Jackson serializes to snake_case.
 */
public class NotificationPreferenceResponse {
  private String type;
  private String channel;
  private boolean enabled;

  private NotificationPreferenceResponse() {}

  public static NotificationPreferenceResponse from(NotificationPreference p) {
    NotificationPreferenceResponse r = new NotificationPreferenceResponse();
    r.type = p.type();
    r.channel = p.channel().dbValue();
    r.enabled = p.enabled();
    return r;
  }

  public String getType() {
    return type;
  }

  public String getChannel() {
    return channel;
  }

  public boolean isEnabled() {
    return enabled;
  }
}
