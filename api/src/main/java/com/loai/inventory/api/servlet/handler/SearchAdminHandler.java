package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.AdminSearchResponse;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.service.platform.PlatformSearchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cross-org identifier lookup at {@code GET /api/admin/search?q=} (slice 3 of the
 * platform-console epic, {@code stories/platform_search.md}) — the way in when an operator starts
 * from an order number a customer quoted rather than from a tile. Gated on {@code
 * requirePlatformRead} (ADMIN or SUPPORT): the whole resource is a read, so both tiers see
 * byte-identical output, and reads stay unaudited like {@code /orgs} and {@code /users}.
 *
 * <p><strong>Routing follows {@link OverviewAdminHandler}, not {@link QueuesAdminHandler}:</strong>
 * {@code GET} only → 405, and <strong>any subpath → 404</strong>. Queues answers an unknown segment
 * with a 400 because its {@code {kind}} is an enum value that happens to sit in the path, so it
 * follows this codebase's {@code ?status=} convention. {@code /search} takes no path segment at all
 * — anything after it is an unknown resource, which is the 404 convention. The query lives in
 * {@code ?q=}, and a bad {@code ?q=} is the 400 here.
 *
 * <p><strong>A result set is never a redirect.</strong> {@code order_number}, {@code
 * invoice_number}, {@code credit_note_number} and {@code customer.email} are unique <em>per
 * org</em> — {@code UNIQUE (org_id, …)} — so the same order number exists in many tenants and a UI
 * that "jumped to the entity" would silently hide the rest. Even a single hit is a group of one.
 *
 * <h2>What this endpoint does not search, with reasons rather than silence</h2>
 *
 * <ul>
 *   <li><strong>Customer names — the exclusion that is a measurement, not a judgement.</strong>
 *       {@code customer} is matched on {@code email} only. {@code customer.name_search} is a V62
 *       generated column with a GIN trigram index and is usable cross-org exactly as it stands, but
 *       <em>pg_trgm extracts no trigram from a pattern shorter than three characters</em>, so a
 *       2-char {@code LIKE '%ab%'} still <em>chooses</em> the index and then rechecks the whole
 *       table through it: <strong>1706 ms</strong> over {@code perfdb}'s 200,000 customers (200,000
 *       rows removed by index recheck, 5249 buffer reads), against 0.045 ms at three characters. A
 *       1.7-second query per keystroke is exactly the denial of service a minimum-length rule
 *       exists to prevent, and this endpoint's minimum of 2 does not prevent it. Three more reasons
 *       compound it: {@code ahm} matches 20,026 customers across 200 tenants, so "showing 5 of
 *       20,026" is a true sentence that helps nobody; there is nowhere to send the other 20,021,
 *       since this admin app has <em>no customer screen at all</em> (zero {@code /customers}
 *       routes); and the support scenario supplies an email, not a name. <strong>What would bring
 *       it back:</strong> a cross-org customer surface to link to, plus a ≥ 3 minimum on that probe
 *       alone, plus an {@code ?org_id=} narrowing so the result set is a work list rather than a
 *       census. Pinned by {@code PlatformSearchIT.customerNameIsNotSearchable} so it cannot grow
 *       back quietly.
 *   <li><strong>{@code sales_invoice} / {@code credit_note} numbers.</strong> Genuinely quoted by
 *       customers, but each costs another cross-org index and both are reachable in two clicks from
 *       the order that owns them. Add them when an operator asks, not speculatively.
 *   <li><strong>Products and listings.</strong> Merchant catalog, not operator triage — the
 *       merchant searches their own catalog on the org plane.
 * </ul>
 *
 * <p><strong>One disclosure decided on purpose:</strong> searching an email reveals which tenants
 * that person shops at. A platform ADMIN can already learn this by other means, so it is not new
 * capability — but it is newly one keystroke, and it is in scope because a support operator handed
 * an email genuinely needs it (the alternative is searching per-tenant, 200 times). Pinned by
 * {@code PlatformSearchIT.sameCustomerEmailInTwoOrgs_returnsBoth}, exactly as {@code to_address}
 * was pinned in slice 2, so removing it later is a visible decision.
 *
 * <p>Which probes a query fires, and how its input is normalized, are both stated once as data in
 * {@code PlatformSearchQuery} — a reviewer can read the whole table there without running anything.
 */
public class SearchAdminHandler implements AdminResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(SearchAdminHandler.class);

  private final PlatformSearchService searchService;
  private final ObjectMapper mapper;

  public SearchAdminHandler(PlatformSearchService searchService, ObjectMapper mapper) {
    this.searchService = searchService;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method, HttpServletRequest req, HttpServletResponse resp, String remaining)
      throws IOException {
    try {
      if (remaining != null && !remaining.isEmpty() && !remaining.equals("/")) {
        writeError(resp, 404, "Unknown search endpoint");
        return;
      }
      if (!"GET".equals(method)) {
        writeError(resp, 405, "Method not allowed");
        return;
      }
      AuthzHelper.requirePlatformRead(req);

      writeJson(resp, 200, AdminSearchResponse.from(searchService.search(req.getParameter("q"))));
    } catch (AppException e) {
      writeError(resp, e.getStatusCode(), e.getMessage());
    } catch (Exception e) {
      log.error("Unexpected error in /api/admin/search{}", remaining, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }
}
