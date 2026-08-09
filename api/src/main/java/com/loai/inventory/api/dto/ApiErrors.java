package com.loai.inventory.api.dto;

import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ApprovalRequiredException;
import com.loai.inventory.common.exception.InsufficientStockException;
import com.loai.inventory.common.exception.TooManyAttemptsException;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The one place an {@link AppException} becomes a response — body shape and any headers the status
 * carries beyond it.
 *
 * <p><b>Why this exists.</b> Most {@code AppException}s are fully described by {@code {status,
 * error, message}}, but a few carry structured data the client genuinely needs, and each one used
 * to be a hand-written branch inside a handler's own private {@code writeError}. That scaled badly
 * in exactly the way you would expect: {@link InsufficientStockException}'s shortage list was
 * mapped in {@code SalesOrderHandler} and {@code FulfillmentHandler} and nowhere else, which was
 * right only because no other route could raise it. {@link ApprovalRequiredException} (D12) is not
 * so lucky — it surfaces from <b>five</b> handlers, because three throw sites fan out through
 * {@code RefundService.createDirectPendingInTx} and {@code OrderCancellationService}. Five copies
 * of one mapping is five chances to cover four of them, and a refusal that is machine-readable on
 * {@code /refunds} but not on {@code /fulfillments/{id}/refund} is worse than one that is
 * machine-readable nowhere: the client cannot tell which it is holding.
 *
 * <p>So the rule is: <b>a handler never decides what an exception looks like on the wire.</b> It
 * calls {@link #body} for the body and {@link #applyHeaders} for the headers, and a new carrying
 * exception is added here once.
 *
 * <p>This does not centralise <em>writing</em> — each handler keeps its own {@code writeJson},
 * which owns its {@code ObjectMapper} and its status. Only the mapping moved, which is the part
 * that was being duplicated.
 */
public final class ApiErrors {

  private ApiErrors() {}

  /**
   * The response body for this exception, including the extra fields its type carries. Jackson
   * omits nulls globally, so an exception with nothing extra produces exactly the envelope it
   * always did.
   */
  public static ApiError body(AppException e) {
    if (e instanceof InsufficientStockException ise) {
      return ApiError.ofShortages(
          e.getStatusCode(),
          e.getMessage(),
          ise.getShortages().stream()
              .map(s -> new ApiError.Shortage(s.productId(), s.requested(), s.available()))
              .toList());
    }
    if (e instanceof ApprovalRequiredException are) {
      return ApiError.ofApprovalRequired(
          e.getStatusCode(),
          e.getMessage(),
          are.getRequiredRole(),
          are.getThresholdAmount(),
          are.getRequestedAmount());
    }
    return ApiError.of(e.getStatusCode(), e.getMessage());
  }

  /**
   * Headers this exception contributes beyond the body. Today only {@code Retry-After} on the
   * per-account login lockout (D9a) — a number that belongs in a header rather than as a field, and
   * the reason this method exists separately from {@link #body}.
   */
  public static void applyHeaders(HttpServletResponse resp, AppException e) {
    if (e instanceof TooManyAttemptsException tooMany) {
      resp.setHeader("Retry-After", String.valueOf(tooMany.getRetryAfterSeconds()));
    }
  }
}
