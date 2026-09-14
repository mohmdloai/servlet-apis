package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * An attempt to collect an order's outstanding amount through a PSP (V99, {@code
 * stories/paymob_card_checkout.md}) — the one new concept the Paymob card epic adds. It remembers
 * three facts that have nowhere else to live: the amount <em>as quoted</em> to the PSP (the order
 * can be repriced), which attempt a callback belongs to (a shopper who retries three times mints
 * three intentions), and whether an attempt is still outstanding (slice 3's sweeper needs a work
 * list). The money itself is a {@link PaymentTransaction} + a {@link Payment}, like every rail.
 *
 * <pre>
 *   [*] → PENDING              minted by POST …/pay; Paymob has an intention for it
 *   PENDING → SETTLED          the webhook attributed a successful transaction to it
 *   PENDING → FAILED           the webhook attributed a declined attempt to it
 *   PENDING → EXPIRED          slice 3's sweeper found no transaction past expires_at
 *   FAILED|EXPIRED → SETTLED   a retry on Paymob's hosted page succeeded after all — money moved,
 *                              and the row must say so (the order decides whether it settles)
 * </pre>
 */
public final class PaymentIntent {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

  public enum Status {
    PENDING,
    SETTLED,
    FAILED,
    EXPIRED
  }

  private final UUID id;
  private final UUID orgId;
  private final UUID salesOrderId;
  private final PaymentProvider provider;
  private final BigDecimal amount;
  private final String currency;
  private final String specialReference;
  private final OffsetDateTime expiresAt;
  private final OffsetDateTime createdAt;

  private String intentionId;
  private String paymobOrderId;
  private String clientSecret;
  private Status status;
  private String settledTxnRef;
  private OffsetDateTime updatedAt;

  /**
   * A fresh PENDING intent. {@code specialReference} is the id itself: Paymob rejects a duplicate
   * per merchant account, and a second attempt for the same order needs a second value.
   */
  public static PaymentIntent create(
      UUID id,
      UUID orgId,
      UUID salesOrderId,
      PaymentProvider provider,
      BigDecimal amount,
      String currency,
      OffsetDateTime expiresAt,
      OffsetDateTime now) {
    Objects.requireNonNull(id, "id required");
    Objects.requireNonNull(orgId, "orgId required");
    Objects.requireNonNull(salesOrderId, "salesOrderId required");
    Objects.requireNonNull(provider, "provider required");
    Objects.requireNonNull(amount, "amount required");
    Objects.requireNonNull(currency, "currency required");
    Objects.requireNonNull(expiresAt, "expiresAt required");
    Objects.requireNonNull(now, "now required");
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("amount must be > 0");
    }
    if (!expiresAt.isAfter(now)) {
      throw new IllegalArgumentException("expiresAt must be in the future");
    }
    return new PaymentIntent(
        id,
        orgId,
        salesOrderId,
        provider,
        amount.setScale(MONEY_SCALE, MONEY_ROUNDING),
        currency,
        id.toString(),
        expiresAt,
        now,
        null,
        null,
        null,
        Status.PENDING,
        null,
        now);
  }

  /** Reconstitute from persistent state — trusts DB invariants. */
  public static PaymentIntent rehydrate(
      UUID id,
      UUID orgId,
      UUID salesOrderId,
      PaymentProvider provider,
      BigDecimal amount,
      String currency,
      String specialReference,
      OffsetDateTime expiresAt,
      OffsetDateTime createdAt,
      String intentionId,
      String paymobOrderId,
      String clientSecret,
      Status status,
      String settledTxnRef,
      OffsetDateTime updatedAt) {
    return new PaymentIntent(
        id,
        orgId,
        salesOrderId,
        provider,
        amount,
        currency,
        specialReference,
        expiresAt,
        createdAt,
        intentionId,
        paymobOrderId,
        clientSecret,
        status,
        settledTxnRef,
        updatedAt);
  }

  private PaymentIntent(
      UUID id,
      UUID orgId,
      UUID salesOrderId,
      PaymentProvider provider,
      BigDecimal amount,
      String currency,
      String specialReference,
      OffsetDateTime expiresAt,
      OffsetDateTime createdAt,
      String intentionId,
      String paymobOrderId,
      String clientSecret,
      Status status,
      String settledTxnRef,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.orgId = orgId;
    this.salesOrderId = salesOrderId;
    this.provider = provider;
    this.amount = amount;
    this.currency = currency;
    this.specialReference = specialReference;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
    this.intentionId = intentionId;
    this.paymobOrderId = paymobOrderId;
    this.clientSecret = clientSecret;
    this.status = status;
    this.settledTxnRef = settledTxnRef;
    this.updatedAt = updatedAt;
  }

  /** Paymob answered: remember its handles. Set once, before the row is first written. */
  public void attachIntention(String intentionId, String paymobOrderId, String clientSecret) {
    this.intentionId = intentionId;
    this.paymobOrderId = paymobOrderId;
    this.clientSecret = clientSecret;
  }

  /**
   * A successful transaction was attributed to this intent. Allowed from any non-settled state — a
   * decline followed by a retry on the same hosted page is one intention with two transactions, and
   * a payment that lands after {@code expires_at} still moved money. A second success on an
   * already-settled intent leaves the first attribution in place (the reconcile has already made
   * that transaction an ORPHAN; the ledger holds both).
   *
   * @return whether this call changed the row
   */
  public boolean settle(String txnRef, OffsetDateTime now) {
    Objects.requireNonNull(now, "now required");
    if (txnRef == null || txnRef.isBlank()) {
      throw new IllegalArgumentException("txnRef required");
    }
    if (status == Status.SETTLED) {
      return false;
    }
    this.status = Status.SETTLED;
    this.settledTxnRef = txnRef;
    this.updatedAt = now;
    return true;
  }

  /**
   * A declined attempt was attributed to this intent. Only from PENDING: a decline after a
   * settlement does not un-settle anything, and an already-expired intent stays expired.
   *
   * @return whether this call changed the row
   */
  public boolean fail(String txnRef, OffsetDateTime now) {
    Objects.requireNonNull(now, "now required");
    if (status != Status.PENDING) {
      return false;
    }
    this.status = Status.FAILED;
    this.settledTxnRef = txnRef;
    this.updatedAt = now;
    return true;
  }

  /** Slice 3's sweeper: no transaction by {@code expires_at}. Only from PENDING. */
  public boolean expire(OffsetDateTime now) {
    Objects.requireNonNull(now, "now required");
    if (status != Status.PENDING) {
      return false;
    }
    this.status = Status.EXPIRED;
    this.updatedAt = now;
    return true;
  }

  /** Still PENDING and not yet past its deadline — reusable by a second tap on "pay". */
  public boolean isLive(OffsetDateTime now) {
    return status == Status.PENDING && expiresAt.isAfter(now);
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public UUID getSalesOrderId() {
    return salesOrderId;
  }

  public PaymentProvider getProvider() {
    return provider;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public String getSpecialReference() {
    return specialReference;
  }

  public OffsetDateTime getExpiresAt() {
    return expiresAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public String getIntentionId() {
    return intentionId;
  }

  public String getPaymobOrderId() {
    return paymobOrderId;
  }

  public String getClientSecret() {
    return clientSecret;
  }

  public Status getStatus() {
    return status;
  }

  public String getSettledTxnRef() {
    return settledTxnRef;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  @Override
  public String toString() {
    return "PaymentIntent{id="
        + id
        + ", order="
        + salesOrderId
        + ", amount="
        + amount
        + " "
        + currency
        + ", status="
        + status
        + "}";
  }
}
