package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.SupportPresignResponse;
import com.loai.inventory.api.dto.SupportTicketRequests;
import com.loai.inventory.api.dto.SupportTicketResponse;
import com.loai.inventory.api.dto.TicketDeskCountsResponse;
import com.loai.inventory.api.filter.JwtAuthFilter;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.TicketStatus;
import com.loai.inventory.service.platform.SupportDeskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /api/admin/tickets} — the support desk ({@code stories/support_tickets.md}). Every route
 * passes {@link AuthzHelper#requireSupportDesk}: ADMIN and SUPPORT alike, reads and writes — the
 * desk is the one place the SUPPORT tier writes, and what it writes changes no tenant data.
 *
 * <pre>
 * GET   /                          ?status=&org_id=&page&size   the inbox (OPEN = the queue)
 * GET   /counts                    ?org_id=                     the tab badges
 * GET   /{id}                                                   the thread, notes included
 * POST  /{id}/messages             {body, attachments?, resolve?}
 * POST  /{id}/resolve
 * POST  /{id}/close
 * POST  /{id}/attachments/presign  {filename, content_type}
 * </pre>
 *
 * Unknown {@code ?status=} → 400 naming the four (the {@code ?status=} convention); malformed
 * {@code org_id} → 400; unknown {@code org_id} → an empty page (a filter, not a lookup).
 */
public class TicketDeskAdminHandler implements AdminResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(TicketDeskAdminHandler.class);

  private final SupportDeskService service;
  private final ObjectMapper mapper;

  public TicketDeskAdminHandler(SupportDeskService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method, HttpServletRequest req, HttpServletResponse resp, String remaining)
      throws IOException {
    try {
      AuthzHelper.requireSupportDesk(req);
      String[] parts = splitPath(remaining);
      if (parts.length == 0) {
        if ("GET".equals(method)) {
          doList(req, resp);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      if (parts.length == 1 && "counts".equals(parts[0])) {
        if ("GET".equals(method)) {
          doCounts(req, resp);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      UUID ticketId = parseId(parts[0]);
      if (parts.length == 1) {
        if ("GET".equals(method)) {
          writeJson(resp, 200, SupportTicketResponse.deskView(service.get(ticketId)));
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      if (parts.length == 2 && "POST".equals(method)) {
        switch (parts[1]) {
          case "messages" -> {
            doReply(req, resp, ticketId);
            return;
          }
          case "resolve" -> {
            writeJson(
                resp,
                200,
                SupportTicketResponse.deskView(service.resolve(sc(req), env(req), ticketId)));
            return;
          }
          case "close" -> {
            writeJson(
                resp,
                200,
                SupportTicketResponse.deskView(service.close(sc(req), env(req), ticketId)));
            return;
          }
          default -> {}
        }
      }
      if (parts.length == 3
          && "attachments".equals(parts[1])
          && "presign".equals(parts[2])
          && "POST".equals(method)) {
        SupportTicketRequests.Presign body = readBody(req, SupportTicketRequests.Presign.class);
        writeJson(
            resp,
            200,
            SupportPresignResponse.from(
                service.presignAttachment(ticketId, body.getFilename(), body.getContentType())));
        return;
      }
      if (parts.length >= 2 && !"POST".equals(method)) {
        writeError(resp, 405, "Method not allowed");
        return;
      }
      writeError(resp, 404, "Not found");
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/admin/tickets{}", remaining, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doList(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    TicketStatus status = SupportDeskService.parseStatus(req.getParameter("status"));
    UUID orgId = uuidParam(req, "org_id");
    int page = intParam(req, "page", 0);
    int size = intParam(req, "size", SupportDeskService.DEFAULT_PAGE_SIZE);
    SupportDeskService.DeskPage result = service.list(status, orgId, page, size);
    List<SupportTicketResponse> data =
        result.rows().stream().map(SupportTicketResponse::deskRow).toList();
    writeJson(resp, 200, new PageResponse<>(data, result.total(), result.page(), result.size()));
  }

  private void doCounts(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    writeJson(resp, 200, TicketDeskCountsResponse.from(service.counts(uuidParam(req, "org_id"))));
  }

  private void doReply(HttpServletRequest req, HttpServletResponse resp, UUID ticketId)
      throws IOException {
    SupportTicketRequests.Message body = readBody(req, SupportTicketRequests.Message.class);
    writeJson(
        resp,
        201,
        SupportTicketResponse.deskView(
            service.reply(
                sc(req),
                env(req),
                ticketId,
                body.getBody(),
                SupportTicketHandler.toAttachments(body.getAttachments()),
                Boolean.TRUE.equals(body.getResolve()))));
  }

  // Plumbing

  private static SecurityContext sc(HttpServletRequest req) {
    return (SecurityContext) req.getAttribute(JwtAuthFilter.SECURITY_CONTEXT_ATTR);
  }

  private static Environment env(HttpServletRequest req) {
    return (Environment) req.getAttribute(JwtAuthFilter.ENVIRONMENT_ATTR);
  }

  private static UUID uuidParam(HttpServletRequest req, String name) {
    String raw = req.getParameter(name);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Parameter '" + name + "' must be a UUID");
    }
  }

  private static int intParam(HttpServletRequest req, String name, int fallback) {
    String raw = req.getParameter(name);
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      throw new ValidationException("Parameter '" + name + "' must be an integer");
    }
  }

  private static String[] splitPath(String remaining) {
    if (remaining == null || remaining.isEmpty() || "/".equals(remaining)) {
      return new String[0];
    }
    String raw = remaining.startsWith("/") ? remaining.substring(1) : remaining;
    if (raw.endsWith("/")) {
      raw = raw.substring(0, raw.length() - 1);
    }
    return raw.isEmpty() ? new String[0] : raw.split("/");
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
