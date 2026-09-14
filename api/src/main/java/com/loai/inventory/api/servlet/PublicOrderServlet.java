package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.PaymentClaimRequest;
import com.loai.inventory.api.dto.PaymentClaimResponse;
import com.loai.inventory.api.dto.PaymentIntentResponse;
import com.loai.inventory.api.dto.PaymentProofPresignRequest;
import com.loai.inventory.api.dto.PaymentProofPresignResponse;
import com.loai.inventory.api.dto.PublicOrderResponse;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.MagicLinkService.ResolvedOrderView;
import com.loai.inventory.service.PaymentIntentService;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.ClaimResult;
import com.loai.inventory.service.ReturnTarget;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.Placed;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Anonymous order routes at {@code /api/public/orders/{token}/…} — the customer side of the
 * notifications email channel (notifications-plan §7). Mounted outside the JWT filter (the {@code
 * /api/public/} bypass); the unguessable {@code VIEW_ORDER} magic token <em>is</em> the
 * authorization, scoped to exactly one order.
 *
 * <ul>
 *   <li>{@code GET /{token}} — the customer-safe order view (the branded status page's data).
 *   <li>{@code POST /{token}/payment-proof/presign} — mint a presigned PUT URL for a payment
 *       screenshot (roadmap item 2). Guests have no JWT, so the STAFF org presign can't be reused —
 *       the token authorizes it, and the minted key is prefix-bound to the token's org + order.
 *   <li>{@code POST /{token}/payment-claim} — record the shopper's InstaPay reference (+ optional
 *       proof key) as an UNVERIFIED transaction in the staff reconciliation queue.
 *   <li>{@code POST /{token}/pay} — mint (or reuse) a Paymob card intention for the outstanding
 *       amount and return the Unified Checkout URL ({@code stories/paymob_card_checkout.md}). The
 *       token is the capability; no new auth concept. Settlement happens on the webhook, never
 *       here.
 * </ul>
 *
 * <p>Every token failure (unknown/expired token, or an order that vanished) answers an opaque
 * {@code 404} — never distinguishing them, so the endpoint is not an oracle for valid tokens.
 */
public class PublicOrderServlet extends HttpServlet {

  private MagicLinkService magicLinkService;
  private SalesOrderService salesOrderService;
  private PaymentTransactionService paymentTransactionService;
  private PaymentIntentService paymentIntentService;
  private ObjectStorage objectStorage;
  private ObjectMapper mapper;

  @Override
  public void init() {
    AppConfig config = (AppConfig) getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.magicLinkService = config.magicLinkService;
    this.salesOrderService = config.salesOrderService;
    this.paymentTransactionService = config.paymentTransactionService;
    this.paymentIntentService = config.paymentIntentService;
    this.objectStorage = config.objectStorage;
    this.mapper = config.objectMapper;
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      String[] parts = splitPath(req.getPathInfo());
      if (parts.length == 0) {
        writeError(resp, 404, "Not found");
        return;
      }
      String token = parts[0];
      String method = req.getMethod();

      // GET /{token} — the order view.
      if (parts.length == 1 && "GET".equals(method)) {
        handleOrderView(resp, token);
        return;
      }
      // POST /{token}/payment-claim — record a shopper payment claim.
      if (parts.length == 2 && "payment-claim".equals(parts[1]) && "POST".equals(method)) {
        handlePaymentClaim(req, resp, token);
        return;
      }
      // POST /{token}/pay — a Paymob card intention for the outstanding amount.
      if (parts.length == 2 && "pay".equals(parts[1]) && "POST".equals(method)) {
        handlePay(resp, token);
        return;
      }
      // POST /{token}/payment-proof/presign — mint an upload URL for the screenshot.
      if (parts.length == 3
          && "payment-proof".equals(parts[1])
          && "presign".equals(parts[2])
          && "POST".equals(method)) {
        handleProofPresign(req, resp, token);
        return;
      }
      writeError(resp, 405, "Method not allowed");
    } catch (AppException e) {
      writeError(resp, e.getStatusCode(), e.getMessage());
    } catch (Exception e) {
      writeError(resp, 500, "Internal server error");
    }
  }

  private void handleOrderView(HttpServletResponse resp, String token) throws IOException {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Optional<ResolvedOrderView> resolved = magicLinkService.resolveOrderView(token, now);
    if (resolved.isEmpty()) {
      writeError(resp, 404, "Not found");
      return;
    }
    ResolvedOrderView view = resolved.get();
    Optional<Placed> placed = salesOrderService.findPlaced(view.orgId(), view.orderId());
    if (placed.isEmpty()) {
      writeError(resp, 404, "Not found");
      return;
    }
    Placed p = placed.get();
    // Customer-safe view: a whitelisted body carrying no internal id, product_id, prepaid, or
    // channel — the same guarantee the checkout confirmation upholds. Plus the shopper's latest
    // claim (pending / confirmed / not found), so the "I've paid" beat survives a reload.
    writeJson(
        resp,
        200,
        PublicOrderResponse.forOrderView(
            p.order(),
            p.lines(),
            paymentTransactionService.latestClaimFor(p.order().getId()).orElse(null)));
  }

  private void handlePaymentClaim(HttpServletRequest req, HttpServletResponse resp, String token)
      throws IOException {
    ResolvedOrderView view = resolveOrThrow(token);
    PaymentClaimRequest body = readBody(req, PaymentClaimRequest.class);
    ClaimResult result =
        paymentTransactionService.claim(
            view.orgId(),
            new PaymentTransactionService.ClaimCommand(
                view.orderId(),
                view.customerId(),
                body.getReference(),
                body.getProofObjectKey(),
                body.getNote()));
    // 201 on first record, 200 on an idempotent replay of the same reference (or on re-opening a
    // claim the store could not find — the same reference, pending again).
    writeJson(
        resp,
        result.inserted() ? 201 : 200,
        PaymentClaimResponse.from(result.transaction(), result.inserted(), result.reopened()));
  }

  private void handlePay(HttpServletResponse resp, String token) throws IOException {
    ResolvedOrderView view = resolveOrThrow(token);
    // 200 on a fresh intention and on the reuse of a live one alike: the shopper asked "where do
    // I pay" and got the answer; whether it was minted now is not their concern. Paymob sends the
    // guest back to the branded tracker at this same token.
    writeJson(
        resp,
        200,
        PaymentIntentResponse.from(
            paymentIntentService.pay(
                view.orgId(),
                view.orderId(),
                view.customerId(),
                ReturnTarget.publicTracker(token))));
  }

  private void handleProofPresign(HttpServletRequest req, HttpServletResponse resp, String token)
      throws IOException {
    ResolvedOrderView view = resolveOrThrow(token);
    PaymentProofPresignRequest body = readBody(req, PaymentProofPresignRequest.class);
    String objectKey =
        objectStorage.newPaymentProofKey(view.orgId(), view.orderId(), body.getFilename());
    String uploadUrl = objectStorage.presignPut(objectKey, body.getContentType());
    writeJson(
        resp,
        200,
        PaymentProofPresignResponse.of(uploadUrl, objectKey, objectStorage.presignTtlSeconds()));
  }

  /** Resolve the token to its order or throw the opaque 404 (never a valid-token oracle). */
  private ResolvedOrderView resolveOrThrow(String token) {
    return magicLinkService
        .resolveOrderView(token, OffsetDateTime.now(ZoneOffset.UTC))
        .orElseThrow(() -> new com.loai.inventory.common.exception.NotFoundException("Not found"));
  }

  /** Split {@code /a/b/c} into {@code [a,b,c]}; missing/empty → {@code []}. */
  private static String[] splitPath(String pathInfo) {
    if (pathInfo == null || pathInfo.length() < 2) {
      return new String[0];
    }
    String raw = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
    if (raw.endsWith("/")) {
      raw = raw.substring(0, raw.length() - 1);
    }
    if (raw.isBlank()) {
      return new String[0];
    }
    return raw.split("/");
  }

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    try {
      T body = mapper.readValue(req.getInputStream(), type);
      if (body == null) {
        throw new ValidationException("request body is required");
      }
      return body;
    } catch (com.fasterxml.jackson.core.JacksonException e) {
      throw new ValidationException("malformed JSON body");
    }
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    resp.setHeader("Cache-Control", "no-store");
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }
}
