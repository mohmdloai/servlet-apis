package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.PaymobConnectRequest;
import com.loai.inventory.api.dto.PaymobStatusResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.service.OrgPaymobService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The merchant's Paymob connection under {@code /api/orgs/{orgId}/paymob} (epic slice 1, {@code
 * stories/paymob_connect.md}).
 *
 * <ul>
 *   <li>{@code GET} — connection status (OWNER). Never returns either secret in any form.
 *   <li>{@code POST} — connect/re-connect with credentials from the Paymob dashboard (OWNER).
 *   <li>{@code DELETE} — disconnect and delete the credentials (OWNER); idempotent.
 * </ul>
 *
 * <p><b>OWNER for every verb, including the read</b> — higher than {@link WhatsAppHandler}'s
 * MANAGER bar, and deliberately so: this resource decides where the org's card revenue is
 * deposited, not merely where notifications go. A MANAGER who could repoint it is a MANAGER who
 * could take the shop's income.
 *
 * <p><b>No sub-routes.</b> Unlike WhatsApp's {@code enable}/{@code disable}, this resource has
 * nothing else to dispatch to — re-POSTing replaces the credentials, there is no separate pause.
 * Any path segment after {@code /paymob}, and any verb this resource doesn't define ({@code PUT},
 * {@code PATCH}), is {@code 405}: the resource exists, the request doesn't name one of its verbs.
 */
public class PaymobHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(PaymobHandler.class);

  private final OrgPaymobService service;
  private final ObjectMapper mapper;

  public PaymobHandler(OrgPaymobService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method, HttpServletRequest req, HttpServletResponse resp, UUID orgId, String remaining)
      throws IOException {
    try {
      if (!isBarePath(remaining)) {
        writeError(resp, 405, "Method not allowed");
        return;
      }
      switch (method) {
        case "GET" -> {
          AuthzHelper.requireOrgAccess(req, orgId, OrgRole.OWNER);
          writeJson(resp, 200, PaymobStatusResponse.from(service.status(orgId)));
        }
        case "POST" -> {
          AuthzHelper.requireOrgAccess(req, orgId, OrgRole.OWNER);
          PaymobConnectRequest body = readBody(req, PaymobConnectRequest.class);
          writeJson(
              resp,
              200,
              PaymobStatusResponse.from(
                  service.connect(
                      orgId,
                      body.getPublicKey(),
                      body.getSecretKey(),
                      body.getHmacSecret(),
                      body.getApiKey(),
                      body.getCardIntegrationId(),
                      body.getRegion())));
        }
        case "DELETE" -> {
          AuthzHelper.requireOrgAccess(req, orgId, OrgRole.OWNER);
          service.disconnect(orgId);
          resp.setStatus(204);
        }
        default -> writeError(resp, 405, "Method not allowed");
      }
    } catch (AppException e) {
      writeError(resp, e.getStatusCode(), e.getMessage());
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/paymob{}", orgId, remaining, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private static boolean isBarePath(String remaining) {
    return remaining == null || remaining.isBlank() || "/".equals(remaining);
  }

  /**
   * A present-but-unparseable body is a client error (400 via {@link ValidationException}), never a
   * swallowed null and never a 500 — connect always requires a body, unlike the WhatsApp
   * enable/disable actions this class otherwise mirrors.
   */
  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    byte[] bytes = req.getInputStream().readAllBytes();
    if (bytes.length == 0 || new String(bytes, StandardCharsets.UTF_8).isBlank()) {
      throw new ValidationException("request body is required");
    }
    try {
      return mapper.readValue(bytes, type);
    } catch (JsonProcessingException e) {
      throw new ValidationException("malformed JSON body");
    }
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    // A credential-bearing surface must never sit in a shared cache, even though the response
    // carries no secret — the connection state itself is operational detail.
    resp.setHeader("Cache-Control", "private, no-store");
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    mapper.writeValue(resp.getOutputStream(), ApiError.of(status, message));
  }
}
