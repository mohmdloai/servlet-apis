package com.loai.inventory.common.exception;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 409 raised by the free-form record path when the order it names has open shopper claims and the
 * reference being recorded is not one of them ({@code stories/payment_claim_verify.md}). Before
 * this guard, that exact call minted a second VERIFIED + MATCHED transaction, flipped the order
 * PAID through it, and left the shopper's own claim UNVERIFIED forever — the phantom.
 *
 * <p>Carries the pending claims so the client can offer "verify it instead" rather than an error:
 * the wire body is {@code {kind: "CLAIM_PENDING", message, claims: [{id, provider_ref, amount,
 * filed_at, has_proof}]}} (see {@code ApiErrors}). The caller proceeds by acknowledging every
 * listed id ({@code acknowledge_claim_ids}) — an explicit "I checked the bank: this is a separate
 * transfer".
 */
public class ClaimPendingException extends ConflictException {

  /** One open claim on the order, as much as the client needs to name it. */
  public record PendingClaim(
      UUID id, String providerRef, BigDecimal amount, OffsetDateTime filedAt, boolean hasProof) {}

  public static final String KIND = "CLAIM_PENDING";

  private final List<PendingClaim> claims;

  public ClaimPendingException(String message, List<PendingClaim> claims) {
    super(message);
    this.claims = List.copyOf(claims);
  }

  public List<PendingClaim> getClaims() {
    return claims;
  }
}
