package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.ShiftMovementResponse;
import com.loai.inventory.api.dto.ShiftPageResponse;
import com.loai.inventory.api.dto.ShiftRequests;
import com.loai.inventory.api.dto.ShiftResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.CashMovementKind;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.CashShiftService;
import com.loai.inventory.service.document.DocumentRenderService;
import com.loai.inventory.service.document.DocumentRenderService.RenderedDocument;
import com.loai.inventory.service.document.Escpos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /api/orgs/{orgId}/shifts} — the cash drawer's day ({@code stories/cash_shift.md}).
 *
 * <pre>
 * GET   /                  list, newest first             VIEWER
 * POST  /                  open {starting_cash, note?}    STAFF
 * GET   /current           the open shift, live totals    VIEWER   (204 when none)
 * GET   /{id}              detail + movements             VIEWER
 * PATCH /{id}              {starting_cash} — fix the float STAFF (own) / MANAGER
 * POST  /{id}/movements    {kind, amount, reason}         STAFF (own) / MANAGER
 * POST  /{id}/close        {counted_cash, note?}          STAFF (own) / MANAGER
 * GET   /{id}/slip.escpos  ?width=576|384                 VIEWER
 * </pre>
 *
 * "Own" is decided in the service from the actor id; the handler only says whether the caller holds
 * manager authority ({@link AuthzHelper#hasManagerAuthority}), the {@code counter_discount.md}
 * precedent.
 */
public class CashShiftHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(CashShiftHandler.class);

  private final CashShiftService service;
  private final DocumentRenderService renderService;
  private final ObjectMapper mapper;

  public CashShiftHandler(
      CashShiftService service, DocumentRenderService renderService, ObjectMapper mapper) {
    this.service = service;
    this.renderService = renderService;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method,
      HttpServletRequest req,
      HttpServletResponse resp,
      UUID orgId,
      String remainingPath)
      throws IOException {
    try {
      String[] parts = splitPath(remainingPath);
      if (parts.length == 0) {
        if ("GET".equals(method)) {
          doList(req, resp, orgId);
        } else if ("POST".equals(method)) {
          doOpen(req, resp, orgId);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      if (parts.length == 1 && "current".equals(parts[0])) {
        if ("GET".equals(method)) {
          doCurrent(req, resp, orgId);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      UUID shiftId = parseId(parts[0]);
      if (parts.length == 1) {
        if ("GET".equals(method)) {
          doGet(req, resp, orgId, shiftId);
        } else if ("PATCH".equals(method)) {
          doFloat(req, resp, orgId, shiftId);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      if (parts.length == 2 && "movements".equals(parts[1]) && "POST".equals(method)) {
        doMovement(req, resp, orgId, shiftId);
        return;
      }
      if (parts.length == 2 && "close".equals(parts[1]) && "POST".equals(method)) {
        doClose(req, resp, orgId, shiftId);
        return;
      }
      if (parts.length == 2 && "slip.escpos".equals(parts[1]) && "GET".equals(method)) {
        doSlip(req, resp, orgId, shiftId);
        return;
      }
      writeError(resp, 404, "Not found");
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/shifts{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doList(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    int page = intParam(req, "page", 0);
    int size = intParam(req, "size", CashShiftService.DEFAULT_PAGE_SIZE);
    writeJson(resp, 200, ShiftPageResponse.from(service.list(orgId, page, size)));
  }

  private void doCurrent(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    Optional<CashShiftService.ShiftView> current = service.current(orgId);
    if (current.isEmpty()) {
      resp.setStatus(204);
      return;
    }
    writeJson(resp, 200, ShiftResponse.from(current.get()));
  }

  private void doGet(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    writeJson(resp, 200, ShiftResponse.from(service.get(orgId, id)));
  }

  private void doOpen(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    ShiftRequests.Open body = readBody(req, ShiftRequests.Open.class);
    writeJson(
        resp,
        201,
        ShiftResponse.from(
            service.open(orgId, body.getStartingCash(), body.getNote(), sc.actorId())));
  }

  private void doFloat(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    ShiftRequests.Float body = readBody(req, ShiftRequests.Float.class);
    writeJson(
        resp,
        200,
        ShiftResponse.from(
            service.setStartingCash(
                orgId,
                id,
                body.getStartingCash(),
                sc.actorId(),
                AuthzHelper.hasManagerAuthority(sc, orgId))));
  }

  private void doMovement(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    ShiftRequests.Movement body = readBody(req, ShiftRequests.Movement.class);
    CashMovementKind kind;
    try {
      kind =
          body.getKind() == null
              ? null
              : CashMovementKind.valueOf(body.getKind().trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("kind must be PAY_IN or PAY_OUT");
    }
    writeJson(
        resp,
        201,
        ShiftMovementResponse.from(
            service.addMovement(
                orgId,
                id,
                kind,
                body.getAmount(),
                body.getReason(),
                sc.actorId(),
                AuthzHelper.hasManagerAuthority(sc, orgId))));
  }

  private void doClose(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    ShiftRequests.Close body = readBody(req, ShiftRequests.Close.class);
    writeJson(
        resp,
        200,
        ShiftResponse.from(
            service.close(
                orgId,
                id,
                body.getCountedCash(),
                body.getNote(),
                sc.actorId(),
                AuthzHelper.hasManagerAuthority(sc, orgId))));
  }

  private void doSlip(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    int width = Escpos.width(req.getParameter("width"));
    RenderedDocument doc =
        renderService.renderShiftSlipEscpos(orgId, service.get(orgId, id), width);
    resp.setStatus(200);
    resp.setContentType("application/octet-stream");
    resp.setContentLength(doc.bytes().length);
    resp.setHeader("Content-Disposition", "attachment; filename=\"" + doc.filename() + "\"");
    resp.setHeader("Cache-Control", "private, no-store");
    resp.getOutputStream().write(doc.bytes());
  }

  // plumbing (mirrors CreditNoteHandler)

  private static int intParam(HttpServletRequest req, String name, int fallback) {
    String raw = req.getParameter(name);
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      throw new ValidationException(name + " must be an integer");
    }
  }

  private static String[] splitPath(String remainingPath) {
    if (remainingPath == null || remainingPath.isEmpty() || "/".equals(remainingPath)) {
      return new String[0];
    }
    String raw = remainingPath.startsWith("/") ? remainingPath.substring(1) : remainingPath;
    if (raw.isEmpty()) {
      return new String[0];
    }
    return raw.split("/");
  }

  private static UUID parseId(String s) {
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid shift id: " + s);
    }
  }

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    try {
      return mapper.readValue(req.getInputStream(), type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ValidationException("request body is required and must be valid JSON");
    }
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writeError(HttpServletResponse resp, AppException e) throws IOException {
    ApiErrors.applyHeaders(resp, e);
    writeJson(resp, e.getStatusCode(), ApiErrors.body(e));
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, com.loai.inventory.api.dto.ApiError.of(status, message));
  }
}
