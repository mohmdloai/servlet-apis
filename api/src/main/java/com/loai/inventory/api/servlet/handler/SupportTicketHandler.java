package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.SupportPresignResponse;
import com.loai.inventory.api.dto.SupportTicketRequests;
import com.loai.inventory.api.dto.SupportTicketResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.TicketCategory;
import com.loai.inventory.domain.model.TicketRef;
import com.loai.inventory.domain.model.TicketRefType;
import com.loai.inventory.domain.model.TicketStatus;
import com.loai.inventory.service.SupportTicketService;
import com.loai.inventory.service.SupportTicketService.AttachmentInput;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /api/orgs/{orgId}/support-tickets} — the merchant's side of the support desk ({@code
 * stories/support_tickets.md}).
 *
 * <pre>
 * GET   /                        ?status=&page&size       VIEWER (STAFF sees own only)
 * POST  /                        open                     STAFF   · 409 TICKET_CAP
 * POST  /attachments/presign     {filename, content_type} STAFF   · 400 off the image allowlist
 * GET   /{id}                    the thread               VIEWER  · opaque 404
 * POST  /{id}/messages           {body, attachments?}     STAFF (own) / MANAGER · 409 TICKET_CLOSED
 * POST  /{id}/close                                       STAFF (own) / MANAGER · 409 TICKET_CLOSED
 * </pre>
 *
 * "Own" is decided in the service from the actor id; the handler only says whether the caller holds
 * manager authority ({@link AuthzHelper#hasManagerAuthority}) — the cash-shift precedent.
 *
 * <p><b>The suspended door</b> ({@code stories/support_ticket_reach.md}): every route here passes
 * {@link AuthzHelper#requireOrgAccessThroughSuspension} — {@code requireOrgAccess} minus the {@code
 * OrgStatusGate} step — because support is the one resource a suspended tenant must still reach.
 * Membership, rank and read-only impersonation apply unchanged; this handler is that variant's only
 * caller, on purpose.
 */
public class SupportTicketHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(SupportTicketHandler.class);

  private final SupportTicketService service;
  private final ObjectMapper mapper;

  public SupportTicketHandler(SupportTicketService service, ObjectMapper mapper) {
    this.service = service;
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
      if (parts.length == 2 && "attachments".equals(parts[0]) && "presign".equals(parts[1])) {
        if ("POST".equals(method)) {
          doPresign(req, resp, orgId);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      UUID ticketId = parseId(parts[0]);
      if (parts.length == 1) {
        if ("GET".equals(method)) {
          doGet(req, resp, orgId, ticketId);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      if (parts.length == 2 && "messages".equals(parts[1])) {
        if ("POST".equals(method)) {
          doMessage(req, resp, orgId, ticketId);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      if (parts.length == 2 && "close".equals(parts[1])) {
        if ("POST".equals(method)) {
          doClose(req, resp, orgId, ticketId);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      writeError(resp, 404, "Not found");
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/support-tickets{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doList(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccessThroughSuspension(req, orgId, OrgRole.VIEWER);
    TicketStatus status = parseStatus(req.getParameter("status"));
    int page = Math.max(intParam(req, "page", 0), 0);
    int size =
        Math.min(
            Math.max(intParam(req, "size", SupportTicketService.DEFAULT_PAGE_SIZE), 1),
            SupportTicketService.MAX_PAGE_SIZE);
    SupportTicketService.TicketPage result =
        service.list(
            orgId, sc.actorId(), AuthzHelper.hasManagerAuthority(sc, orgId), status, page, size);
    List<SupportTicketResponse> data =
        result.items().stream().map(SupportTicketResponse::summary).toList();
    writeJson(resp, 200, new PageResponse<>(data, result.total(), page, size));
  }

  private void doOpen(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccessThroughSuspension(req, orgId, OrgRole.STAFF);
    SupportTicketRequests.Open body = readBody(req, SupportTicketRequests.Open.class);
    TicketCategory category;
    try {
      category = TicketCategory.parse(body.getCategory());
    } catch (IllegalArgumentException e) {
      throw new ValidationException(
          "category must be one of " + java.util.Arrays.toString(TicketCategory.values()));
    }
    SupportTicketService.OpenCommand cmd =
        new SupportTicketService.OpenCommand(
            category,
            body.getSubject(),
            body.getBody(),
            Boolean.TRUE.equals(body.getBlocking()),
            toRef(body.getRef()),
            toAttachments(body.getAttachments()));
    writeJson(resp, 201, SupportTicketResponse.view(service.open(orgId, sc.actorId(), cmd)));
  }

  private void doPresign(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccessThroughSuspension(req, orgId, OrgRole.STAFF);
    SupportTicketRequests.Presign body = readBody(req, SupportTicketRequests.Presign.class);
    writeJson(
        resp,
        200,
        SupportPresignResponse.from(
            service.presignAttachment(orgId, body.getFilename(), body.getContentType())));
  }

  private void doGet(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccessThroughSuspension(req, orgId, OrgRole.VIEWER);
    writeJson(
        resp,
        200,
        SupportTicketResponse.view(
            service.get(orgId, sc.actorId(), AuthzHelper.hasManagerAuthority(sc, orgId), id)));
  }

  private void doMessage(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccessThroughSuspension(req, orgId, OrgRole.STAFF);
    SupportTicketRequests.Message body = readBody(req, SupportTicketRequests.Message.class);
    writeJson(
        resp,
        201,
        SupportTicketResponse.view(
            service.post(
                orgId,
                sc.actorId(),
                AuthzHelper.hasManagerAuthority(sc, orgId),
                id,
                body.getBody(),
                toAttachments(body.getAttachments()))));
  }

  private void doClose(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccessThroughSuspension(req, orgId, OrgRole.STAFF);
    writeJson(
        resp,
        200,
        SupportTicketResponse.view(
            service.close(orgId, sc.actorId(), AuthzHelper.hasManagerAuthority(sc, orgId), id)));
  }

  // Mapping

  static TicketStatus parseStatus(String raw) {
    try {
      return TicketStatus.fromWire(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException(
          "Unknown status: '" + raw + "'. Expected one of: " + TicketStatus.allWireValues());
    }
  }

  static List<AttachmentInput> toAttachments(List<SupportTicketRequests.Attachment> raw) {
    if (raw == null) {
      return List.of();
    }
    return raw.stream()
        .map(
            a ->
                a == null
                    ? null
                    : new AttachmentInput(a.getObjectKey(), a.getContentType(), a.getFileName()))
        .toList();
  }

  static TicketRef toRef(SupportTicketRequests.Ref raw) {
    if (raw == null) {
      return null;
    }
    TicketRefType type;
    try {
      type =
          raw.getType() == null
              ? null
              : TicketRefType.valueOf(raw.getType().trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ValidationException(
          "ref.type must be one of " + java.util.Arrays.toString(TicketRefType.values()));
    }
    return new TicketRef(type, raw.getId(), raw.getLabel());
  }

  // Plumbing (mirrors CashShiftHandler)

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
      throw new ValidationException("Invalid ticket id: " + s);
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
    writeJson(resp, status, ApiError.of(status, message));
  }
}
