package com.loai.inventory.api.dto;

import java.util.List;

/**
 * Body for {@code PUT /api/orgs/{orgId}/notification-preferences}: a list of preferences to upsert
 * (merge) for the calling user. Jackson data carrier (snake_case JSON ↔ camelCase Java). {@code
 * channel} is the DB literal ({@code in_app}/{@code email}); validation lives in the service.
 */
public class NotificationPreferencesRequest {

  private List<Item> preferences;

  public NotificationPreferencesRequest() {}

  public List<Item> getPreferences() {
    return preferences;
  }

  public void setPreferences(List<Item> preferences) {
    this.preferences = preferences;
  }

  /** One preference to upsert. */
  public static class Item {
    private String type;
    private String channel;
    private Boolean enabled;

    public Item() {}

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public String getChannel() {
      return channel;
    }

    public void setChannel(String channel) {
      this.channel = channel;
    }

    public Boolean getEnabled() {
      return enabled;
    }

    public void setEnabled(Boolean enabled) {
      this.enabled = enabled;
    }
  }
}
