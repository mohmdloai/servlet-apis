package com.loai.inventory.api.dto;

import com.loai.inventory.service.OrgWhatsAppService;

/**
 * The merchant's WhatsApp connection, as a settings screen sees it.
 *
 * <p>There is deliberately <b>no token field of any kind</b> — not the value, not a masked prefix,
 * not a "last four". A credential that can message this merchant's customers as them has no
 * read-back that is worth the risk of one being logged, screenshotted or cached; {@code connected}
 * plus the number is the whole answer a settings screen needs. Nulls are omitted, so a
 * never-connected org answers with just {@code {"connected": false}}.
 */
public class WhatsAppStatusResponse {
  private boolean connected;
  private String wabaId;
  private String phoneNumberId;
  private String displayPhoneNumber;
  private String status;

  public static WhatsAppStatusResponse from(OrgWhatsAppService.ConnectionStatus s) {
    WhatsAppStatusResponse r = new WhatsAppStatusResponse();
    r.connected = s.connected();
    r.wabaId = s.wabaId();
    r.phoneNumberId = s.phoneNumberId();
    r.displayPhoneNumber = s.displayPhoneNumber();
    r.status = s.status() == null ? null : s.status().name();
    return r;
  }

  public boolean isConnected() {
    return connected;
  }

  public String getWabaId() {
    return wabaId;
  }

  public String getPhoneNumberId() {
    return phoneNumberId;
  }

  public String getDisplayPhoneNumber() {
    return displayPhoneNumber;
  }

  public String getStatus() {
    return status;
  }
}
