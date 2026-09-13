package com.loai.inventory.api.dto;

import com.loai.inventory.service.SupportTicketService;

/** {@code POST …/attachments/presign} — the presigned PUT the client uploads a screenshot to. */
public record SupportPresignResponse(String uploadUrl, String objectKey, long expiresInSeconds) {
  public static SupportPresignResponse from(SupportTicketService.Presign p) {
    return new SupportPresignResponse(p.uploadUrl(), p.objectKey(), p.expiresInSeconds());
  }
}
