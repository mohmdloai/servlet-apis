package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.AccountStatementResponse;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.JournalPageResponse;
import com.loai.inventory.api.dto.LedgerHealthResponse;
import com.loai.inventory.api.dto.LedgerRebuildResponse;
import com.loai.inventory.api.dto.TrialBalanceResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.service.LedgerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /api/orgs/{orgId}/ledger} — the general ledger (stories/general_ledger.md).
 *
 * <pre>
 * GET  /trial-balance?from=&to=                  every account: opening / debit / credit / closing   MANAGER
 * GET  /journal?from=&to=&account=&page=&size=   posted entries with their legs                     MANAGER
 * GET  /accounts/{code}/lines?from=&to=&page=&size=  one account's statement, running balance       MANAGER
 * GET  /health                                   unposted / unbalanced / cross-checks                MANAGER
 * POST /rebuild[?reset=true]                     re-derive the journal                               OWNER
 * </pre>
 *
 * <p><b>MANAGER for every read</b>: the ledger carries cost of goods, margins and the owner's
 * drawings — the same plane as {@code /reports/profit} (stories/product_cost_and_margin.md), so the
 * same gate, applied whole rather than per field. <b>OWNER for the rebuild</b>: it rewrites derived
 * data for the whole org. Every read catches the ledger up first (see {@code LedgerService}); the
 * writes that implies are idempotent materialisation, not a business mutation. A mutating verb on a
 * read route is 405; an unknown route 404; a bad window / page / account code 400 from the service.
 */
public class LedgerHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(LedgerHandler.class);
  private static final Pattern STATEMENT = Pattern.compile("^/accounts/([0-9]{4})/lines/?$");

  private final LedgerService service;
  private final ObjectMapper mapper;

  public LedgerHandler(LedgerService service, ObjectMapper mapper) {
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
      String path = normalise(remainingPath);
      Matcher statement = STATEMENT.matcher(path);
      switch (path) {
        case "/trial-balance" -> {
          requireGet(method);
          AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
          writeJson(
              resp,
              200,
              TrialBalanceResponse.from(
                  service.trialBalance(orgId, req.getParameter("from"), req.getParameter("to"))));
        }
        case "/journal" -> {
          requireGet(method);
          AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
          writeJson(
              resp,
              200,
              JournalPageResponse.from(
                  service.journal(
                      orgId,
                      req.getParameter("from"),
                      req.getParameter("to"),
                      req.getParameter("account"),
                      req.getParameter("page"),
                      req.getParameter("size"))));
        }
        case "/health" -> {
          requireGet(method);
          AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
          writeJson(resp, 200, LedgerHealthResponse.from(service.health(orgId)));
        }
        case "/rebuild" -> {
          if (!"POST".equals(method)) {
            writeError(resp, 405, "Method not allowed");
            return;
          }
          AuthzHelper.requireOrgAccess(req, orgId, OrgRole.OWNER);
          boolean reset = "true".equalsIgnoreCase(req.getParameter("reset"));
          writeJson(resp, 200, LedgerRebuildResponse.from(reset, service.rebuild(orgId, reset)));
        }
        default -> {
          if (statement.matches()) {
            requireGet(method);
            AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
            writeJson(
                resp,
                200,
                AccountStatementResponse.from(
                    service.statement(
                        orgId,
                        statement.group(1),
                        req.getParameter("from"),
                        req.getParameter("to"),
                        req.getParameter("page"),
                        req.getParameter("size"))));
            return;
          }
          writeError(resp, 404, "Unknown ledger route: " + remainingPath);
        }
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/ledger{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  /**
   * A read route with a mutating verb is a 405, before any auth — the route exists, the verb is
   * wrong.
   */
  private static void requireGet(String method) {
    if (!"GET".equals(method)) {
      throw new MethodNotAllowed();
    }
  }

  private static final class MethodNotAllowed extends AppException {
    MethodNotAllowed() {
      super(405, "Method not allowed");
    }
  }

  private static String normalise(String remainingPath) {
    if (remainingPath == null || remainingPath.isEmpty()) {
      return "/";
    }
    String p = remainingPath.startsWith("/") ? remainingPath : "/" + remainingPath;
    return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
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
