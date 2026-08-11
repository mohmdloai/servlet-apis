package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.WhatsAppConnectRequest;
import com.loai.inventory.api.dto.WhatsAppStatusResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.service.OrgWhatsAppService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * The merchant's WhatsApp connection under {@code /api/orgs/{orgId}/whatsapp} (slice B).
 *
 * <ul>
 *   <li>{@code GET} — connection status (MANAGER). Never returns the access token in any form.
 *   <li>{@code POST} — connect/re-connect with credentials from Meta's WhatsApp Manager (OWNER).
 *   <li>{@code POST /enable} · {@code POST /disable} — pause or resume sending, keeping the
 *       credentials (OWNER).
 *   <li>{@code DELETE} — disconnect and delete the credentials (OWNER); idempotent.
 * </ul>
 *
 * <p><b>Gated OWNER for every write, and MANAGER even to read.</b> Higher than the STAFF bar most
 * merchandising endpoints use, and deliberately so: this stores a credential that lets the platform
 * message the merchant's customers *as* them, and Meta bills them per message. That is the same
 * class of authority as the money-shaped endpoints, not the catalog ones. The read is MANAGER
 * rather than VIEWER for the same reason it is not public — knowing which number a store sends from
 * is operational, not browsing.
 */
public class WhatsAppHandler implements OrgResourceHandler {

  private final OrgWhatsAppService service;
  private final ObjectMapper mapper;

  public WhatsAppHandler(OrgWhatsAppService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method, HttpServletRequest req, HttpServletResponse resp, UUID orgId, String remaining)
      throws IOException {
    try {
      String[] parts = split(remaining);
      if (parts.length == 0) {
        switch (method) {
          case "GET" -> {
            AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
            writeJson(resp, 200, WhatsAppStatusResponse.from(service.status(orgId)));
          }
          case "POST" -> {
            AuthzHelper.requireOrgAccess(req, orgId, OrgRole.OWNER);
            WhatsAppConnectRequest body = readBody(req, WhatsAppConnectRequest.class);
            writeJson(
                resp,
                200,
                WhatsAppStatusResponse.from(
                    service.connect(
                        orgId,
                        body.getWabaId(),
                        body.getPhoneNumberId(),
                        body.getDisplayPhoneNumber(),
                        body.getAccessToken())));
          }
          case "DELETE" -> {
            AuthzHelper.requireOrgAccess(req, orgId, OrgRole.OWNER);
            service.disconnect(orgId);
            resp.setStatus(204);
          }
          default -> writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      if (parts.length == 1 && ("enable".equals(parts[0]) || "disable".equals(parts[0]))) {
        if (!"POST".equals(method)) {
          writeError(resp, 405, "Method not allowed");
          return;
        }
        AuthzHelper.requireOrgAccess(req, orgId, OrgRole.OWNER);
        writeJson(
            resp,
            200,
            WhatsAppStatusResponse.from(service.setEnabled(orgId, "enable".equals(parts[0]))));
        return;
      }
      writeError(resp, 404, "Not found");
    } catch (AppException e) {
      writeError(resp, e.getStatusCode(), e.getMessage());
    }
  }

  private static String[] split(String remaining) {
    if (remaining == null || remaining.isBlank() || "/".equals(remaining)) {
      return new String[0];
    }
    return remaining.replaceAll("^/", "").replaceAll("/$", "").split("/");
  }

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    return mapper.readValue(req.getInputStream(), type);
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    // A credential-bearing surface must never sit in a shared cache, even though the response
    // carries no token — the connection state itself is operational detail.
    resp.setHeader("Cache-Control", "private, no-store");
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    mapper.writeValue(
        resp.getOutputStream(), com.loai.inventory.api.dto.ApiError.of(status, message));
  }
}
