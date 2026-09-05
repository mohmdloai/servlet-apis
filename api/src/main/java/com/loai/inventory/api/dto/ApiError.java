package com.loai.inventory.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Uniform error response body for all non-2xx responses. */
public class ApiError {

  private int status;
  private String error;
  private String message;

  // Optional, only populated for 409 InsufficientStockException. Jackson is configured to omit
  // null fields globally, so callers see this only when it actually carries data.
  private List<Shortage> shortages;

  // Optional, only populated for the 403 ApprovalRequiredException (D12). Same null-omission rule:
  // an ordinary 403 is byte-identical to what it was before these existed.
  private String requiredRole;
  private BigDecimal thresholdAmount;
  private BigDecimal requestedAmount;

  // Optional, only populated for the 409 ClaimPendingException (stories/payment_claim_verify.md):
  // `kind` names the refusal so a client can branch on it without parsing prose, and `claims`
  // carries the open claims to offer "verify it instead". Same null-omission rule.
  private String kind;
  private List<PendingClaim> claims;

  public ApiError(int status, String error, String message) {
    this.status = status;
    this.error = error;
    this.message = message;
  }

  public static ApiError of(int status, String message) {
    return new ApiError(status, httpPhrase(status), message);
  }

  public static ApiError ofShortages(int status, String message, List<Shortage> shortages) {
    ApiError e = new ApiError(status, httpPhrase(status), message);
    e.shortages = shortages;
    return e;
  }

  /**
   * The above-threshold refusal, carrying what the client needs to say "this needs OWNER approval"
   * instead of "you don't have permission" — see {@code ApprovalRequiredException}.
   */
  public static ApiError ofApprovalRequired(
      int status,
      String message,
      String requiredRole,
      BigDecimal thresholdAmount,
      BigDecimal requestedAmount) {
    ApiError e = new ApiError(status, httpPhrase(status), message);
    e.requiredRole = requiredRole;
    e.thresholdAmount = thresholdAmount;
    e.requestedAmount = requestedAmount;
    return e;
  }

  /**
   * The record path's "this order has open claims" refusal — not an error the user did anything
   * wrong to earn, so the client opens the claim's verify sheet instead of showing it.
   */
  /**
   * A plain error whose {@code kind} names a machine-readable reason (e.g. {@code SHIFT_REQUIRED}).
   */
  public static ApiError ofKind(int status, String message, String kind) {
    ApiError e = new ApiError(status, httpPhrase(status), message);
    e.kind = kind;
    return e;
  }

  public static ApiError ofClaimPending(
      int status, String message, String kind, List<PendingClaim> claims) {
    ApiError e = new ApiError(status, httpPhrase(status), message);
    e.kind = kind;
    e.claims = claims;
    return e;
  }

  public int getStatus() {
    return status;
  }

  public String getKind() {
    return kind;
  }

  public List<PendingClaim> getClaims() {
    return claims;
  }

  public String getError() {
    return error;
  }

  public String getMessage() {
    return message;
  }

  public List<Shortage> getShortages() {
    return shortages;
  }

  public String getRequiredRole() {
    return requiredRole;
  }

  public BigDecimal getThresholdAmount() {
    return thresholdAmount;
  }

  public BigDecimal getRequestedAmount() {
    return requestedAmount;
  }

  public record Shortage(UUID productId, int requested, int available) {}

  /** One open shopper claim on the order the record path was aimed at. */
  public record PendingClaim(
      UUID id, String providerRef, BigDecimal amount, OffsetDateTime filedAt, boolean hasProof) {}

  private static String httpPhrase(int code) {
    return switch (code) {
      case 400 -> "Bad Request";
      case 401 -> "Unauthorized";
      case 403 -> "Forbidden";
      case 404 -> "Not Found";
      case 409 -> "Conflict";
      case 422 -> "Unprocessable Entity";
      case 429 -> "Too Many Requests";
      case 500 -> "Internal Server Error";
      default -> "Error";
    };
  }
}
