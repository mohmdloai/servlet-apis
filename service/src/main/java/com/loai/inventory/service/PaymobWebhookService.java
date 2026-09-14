package com.loai.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.common.crypto.PaymobSignature;
import com.loai.inventory.common.crypto.SecretBox;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.OrgPaymobConfig;
import com.loai.inventory.domain.model.PaymentIntent;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.repository.OrgPaymobConfigRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentIntentRepository;
import com.loai.inventory.domain.repository.PaymentIntentRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentTransactionRepository;
import com.loai.inventory.domain.repository.PaymentTransactionRepositoryFactory;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import com.loai.inventory.service.PaymentService.CurrencyMismatch;
import com.loai.inventory.service.PaymentService.OrderRef;
import com.loai.inventory.service.PaymentService.Reconciliation;
import com.loai.inventory.service.paymob.PaymobCallback;
import com.loai.inventory.service.paymob.PaymobHosts;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only writer of card money: Paymob's transaction callback, {@code POST
 * /api/psp/paymob/{orgId}/webhook} ({@code stories/paymob_card_checkout.md}). The sibling of {@link
 * PaymentTransactionService#verify} for a system actor — a signed webhook <em>is</em> the
 * verification ({@code state-machines.md:225}), and everything downstream of "a verified CREDIT
 * transaction exists" is the InstaPay machinery unchanged: {@code insertIfAbsent} on {@code UNIQUE
 * (provider, provider_ref)} is the dedupe, {@link PaymentService#reconcileAndCreate} is the
 * settlement, {@code abandonOpenClaims} closes a sibling InstaPay claim.
 *
 * <p><b>The browser redirect never reaches this class.</b> It is a client-side navigation the
 * shopper can edit, drop, or never make (they close the tab; the card still charged). The return
 * page polls the order; this callback is the writer.
 *
 * <p>The {@link Outcome} is a control signal for Paymob's retry loop, not a status report: {@code
 * REJECTED} → 400 (unverifiable — never retry a forgery), everything else → 200 (done, or a retry
 * would not change the decision). A transient failure — DB down, lock timeout — propagates as an
 * exception and the servlet answers 500 so Paymob <em>does</em> retry: the one case retries exist
 * for.
 */
public final class PaymobWebhookService {

  private static final Logger log = LoggerFactory.getLogger(PaymobWebhookService.class);

  /** {@code verification_proof} on every gateway-verified row: the mechanism, for the ledger. */
  static final String PROOF = "Paymob webhook (HMAC-SHA512 verified)";

  /** Why a callback was answered the way it was. Every kind but {@code REJECTED} is a 200. */
  public record Outcome(Kind kind, String detail) {
    public enum Kind {
      /** Unverifiable: no active config, malformed body, bad signature. 400, nothing written. */
      REJECTED,
      /** Verified and deliberately not acted on: pending, TOKEN, auth-only, refund/void. */
      IGNORED,
      /** Recorded, VERIFIED, reconciled against the intent's order; the order may now be PAID. */
      SETTLED,
      /** This {@code obj.id} was already recorded — idempotent replay, no further writes. */
      REPLAYED,
      /** A declined attempt: recorded as a closed row, the intent FAILED, the order untouched. */
      FAILED,
      /** Money arrived that matched no intent / the wrong amount / a closed order: ORPHAN row. */
      ORPHAN
    }

    public boolean rejected() {
      return kind == Kind.REJECTED;
    }

    static Outcome of(Kind kind, String detail) {
      return new Outcome(kind, detail);
    }
  }

  private final DSLContext rootDsl;
  private final ObjectMapper mapper;
  private final OrgPaymobConfigRepositoryFactory configRepoFactory;
  private final SecretBox secretBox;
  private final PaymentIntentRepositoryFactory intentRepoFactory;
  private final PaymentTransactionRepositoryFactory txnRepoFactory;
  private final SalesOrderRepositoryFactory salesOrderRepoFactory;
  private final PaymentService paymentService;

  public PaymobWebhookService(
      DSLContext rootDsl,
      ObjectMapper mapper,
      OrgPaymobConfigRepositoryFactory configRepoFactory,
      SecretBox secretBox,
      PaymentIntentRepositoryFactory intentRepoFactory,
      PaymentTransactionRepositoryFactory txnRepoFactory,
      SalesOrderRepositoryFactory salesOrderRepoFactory,
      PaymentService paymentService) {
    this.rootDsl = rootDsl;
    this.mapper = mapper;
    this.configRepoFactory = configRepoFactory;
    this.secretBox = secretBox;
    this.intentRepoFactory = intentRepoFactory;
    this.txnRepoFactory = txnRepoFactory;
    this.salesOrderRepoFactory = salesOrderRepoFactory;
    this.paymentService = paymentService;
  }

  /**
   * Handle one delivery. {@code orgId} comes from the URL we minted into the intention's {@code
   * notification_url}; it is attacker-supplied and that is fine — it selects <em>which key to check
   * against</em> and grants nothing on its own (epic §Webhook routing).
   *
   * @param rawBody the body exactly as delivered — it is what gets signed and what gets kept
   * @param presentedHmac the {@code ?hmac=} query parameter, or null
   */
  public Outcome handle(UUID orgId, String rawBody, String presentedHmac) {
    // 1. Which key. No config, or a disabled one, is unverifiable input: 400, not 200.
    Optional<OrgPaymobConfig> config =
        configRepoFactory.create(rootDsl).findByOrgId(orgId).filter(OrgPaymobConfig::isActive);
    if (config.isEmpty()) {
      log.warn("Paymob webhook for org {} with no active Paymob config — rejected", orgId);
      return Outcome.of(Outcome.Kind.REJECTED, "no active Paymob configuration for this org");
    }

    // 2. Parse. Anything that is not an object with an obj object cannot be verified.
    PaymobCallback cb;
    try {
      cb = PaymobCallback.parse(mapper, rawBody);
    } catch (IllegalArgumentException e) {
      log.warn("Paymob webhook for org {} unparseable: {}", orgId, e.getMessage());
      return Outcome.of(Outcome.Kind.REJECTED, "malformed body");
    }

    // 3. Not a transaction. A TOKEN (saved-card) callback signs a DIFFERENT field list, so it is
    //    acknowledged before the transaction signature is checked — it writes nothing, so there is
    //    nothing to protect; a 400 here would only make Paymob retry a delivery we will never use.
    if (!cb.isTransaction()) {
      log.info(
          "Paymob webhook for org {} of type {} — acknowledged, not a transaction",
          orgId,
          cb.type());
      return Outcome.of(Outcome.Kind.IGNORED, "not a transaction callback");
    }

    // 4. The signature: the capability. A failed check is a 400 — a forgery must not be retried.
    if (!secretBox.isConfigured()) {
      // Our misconfiguration, not the caller's: throw so the servlet answers 500 and Paymob keeps
      // retrying until the operator sets the key — a 400 would make it give up.
      throw new IllegalStateException("PAYMOB_CREDENTIAL_KEY is not configured; cannot verify");
    }
    String hmacSecret = secretBox.decrypt(config.get().hmacSecretEncrypted());
    if (!PaymobSignature.verify(cb.signedValues(), hmacSecret, presentedHmac)) {
      log.warn(
          "Paymob webhook for org {} failed HMAC verification (txn {}) — rejected",
          orgId,
          cb.transactionId());
      return Outcome.of(Outcome.Kind.REJECTED, "signature mismatch");
    }

    String txnId = cb.transactionId();
    if (txnId == null || txnId.isBlank()) {
      return Outcome.of(Outcome.Kind.REJECTED, "transaction has no id");
    }

    // 5. Verified but deliberately not acted on.
    if (cb.pending()) {
      // A 3DS challenge in flight. It shares obj.id with the final callback: recording it would
      // burn the provider_ref and the real payment would never settle. 200, no write.
      log.info("Paymob txn {} for org {} is pending — acknowledged, nothing written", txnId, orgId);
      return Outcome.of(Outcome.Kind.IGNORED, "pending");
    }
    if (cb.isAuth() && !cb.isCapture()) {
      log.warn(
          "Paymob txn {} for org {} is auth-only (no capture) — v1 requires a capture-on-sale"
              + " integration; not settled",
          txnId,
          orgId);
      return Outcome.of(Outcome.Kind.IGNORED, "auth-only transaction");
    }
    if (cb.hasParentTransaction() || ((cb.isRefunded() || cb.isVoided()) && !cb.success())) {
      log.warn(
          "Paymob txn {} for org {} is a refund/void (parent={}, refunded={}, voided={}) —"
              + " acknowledged; DEBIT recording is slice 3",
          txnId,
          orgId,
          cb.hasParentTransaction(),
          cb.isRefunded(),
          cb.isVoided());
      return Outcome.of(Outcome.Kind.IGNORED, "refund or void");
    }
    long amountCents = cb.amountCents();
    if (amountCents <= 0) {
      log.warn(
          "Paymob txn {} for org {} carries amount_cents {} — rejected", txnId, orgId, amountCents);
      return Outcome.of(Outcome.Kind.REJECTED, "invalid amount");
    }

    // 6–8. Everything that writes, in one transaction.
    ZoneId paymobZone = PaymobHosts.zoneForRegion(config.get().region());
    return rootDsl.transactionResult(cfg -> record(DSL.using(cfg), orgId, cb, rawBody, paymobZone));
  }

  private Outcome record(
      DSLContext txDsl, UUID orgId, PaymobCallback cb, String rawBody, ZoneId paymobZone) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime occurredAt = cb.createdAt(paymobZone);
    PaymentTransactionRepository txnRepo = txnRepoFactory.create(txDsl);
    PaymentIntentRepository intentRepo = intentRepoFactory.create(txDsl);

    String txnId = cb.transactionId();
    BigDecimal amount = BigDecimal.valueOf(cb.amountCents(), 2);
    String currency = cb.currency() == null ? "EGP" : cb.currency().strip().toUpperCase();

    // 6. Which attempt. Paymob echoes special_reference as order.merchant_order_id (unsigned) and
    //    our extras as payment_key_claims.extra (unsigned); the SIGNED binding is order.id against
    //    the paymob_order_id remembered at intention time. Absent → the money still leaves a row.
    Optional<PaymentIntent> found = resolveIntent(intentRepo, orgId, cb);
    PaymentIntent intent = found.orElse(null);
    SalesOrder order =
        intent == null
            ? null
            : salesOrderRepoFactory
                .create(txDsl)
                .findById(orgId, intent.getSalesOrderId())
                .orElse(null);

    // 8a. A declined attempt: the ledger says the attempt happened; nothing else moves.
    if (!cb.isSettled()) {
      PaymentTransaction declined =
          PaymentTransaction.createClaimed(
              UUID.randomUUID(),
              orgId,
              PaymentProvider.PAYMOB_CARD,
              txnId,
              amount,
              currency,
              order == null ? null : order.getCustomerId(),
              // Deliberately NOT the order: a closed card attempt must not become the order's
              // "latest claim" and hide a live InstaPay claim from the shopper's status page.
              null,
              null,
              null,
              PROOF,
              occurredAt,
              now);
      declined.attachAudit(rawBody);
      declined.abandon(now); // closed by the system, never by a button — the ledger's "declined"
      PaymentTransactionRepository.Recorded rec = txnRepo.insertIfAbsent(declined);
      if (!rec.inserted()) {
        log.info("Paymob txn {} for org {} already recorded — replay", txnId, orgId);
        return Outcome.of(Outcome.Kind.REPLAYED, "already recorded");
      }
      if (intent != null && intent.fail(txnId, now)) {
        intentRepo.update(intent);
      }
      log.info(
          "Paymob txn {} for org {} declined (intent {}) — recorded, order untouched",
          txnId,
          orgId,
          intent == null ? "-" : intent.getId());
      return Outcome.of(Outcome.Kind.FAILED, "declined");
    }

    // 8b. Money moved. Record it VERIFIED first — whatever happens next, the row exists.
    PaymentTransaction txn =
        PaymentTransaction.createClaimed(
            UUID.randomUUID(),
            orgId,
            PaymentProvider.PAYMOB_CARD,
            txnId,
            amount,
            currency,
            order == null ? null : order.getCustomerId(),
            intent == null ? null : intent.getSalesOrderId(),
            null,
            null,
            PROOF,
            occurredAt,
            now);
    txn.attachAudit(rawBody);
    txn.verifyByGateway(PROOF, now);
    PaymentTransactionRepository.Recorded rec = txnRepo.insertIfAbsent(txn);
    if (!rec.inserted()) {
      // Two deliveries of one event serialise on the UNIQUE insert; the loser lands here.
      log.info(
          "Paymob txn {} for org {} already recorded ({}) — idempotent replay",
          txnId,
          orgId,
          rec.transaction().getReconciliationStatus());
      return Outcome.of(Outcome.Kind.REPLAYED, "already recorded");
    }
    txn = rec.transaction();

    // 7. The cross-check against what we ASKED for — the intent, not only the order. An intent
    //    minted for an older, cheaper total must never settle a repriced order; a callback whose
    //    signed Paymob order is not this intent's must never settle it either.
    String orphanReason = null;
    if (intent == null) {
      orphanReason = "no payment intent matches this callback";
    } else if (intent.getPaymobOrderId() != null
        && cb.paymobOrderId() != null
        && !intent.getPaymobOrderId().equals(cb.paymobOrderId())) {
      orphanReason =
          "signed Paymob order "
              + cb.paymobOrderId()
              + " is not the intent's "
              + intent.getPaymobOrderId();
    } else if (intent.getAmount().compareTo(amount) != 0) {
      orphanReason = "amount " + amount + " differs from the intent's " + intent.getAmount();
    } else if (!intent.getCurrency().equalsIgnoreCase(currency)) {
      orphanReason = "currency " + currency + " differs from the intent's " + intent.getCurrency();
    }
    if (orphanReason != null) {
      txn.applyReconciliation(PaymentReconciliationStatus.ORPHAN, now);
      txnRepo.update(txn);
      log.warn(
          "Paymob txn {} for org {} recorded as ORPHAN: {} (intent {})",
          txnId,
          orgId,
          orphanReason,
          intent == null ? "-" : intent.getId());
      return Outcome.of(Outcome.Kind.ORPHAN, orphanReason);
    }

    // 8c. Settle — the InstaPay path from here: lock the order, MATCHED/UNDER/OVER/ORPHAN, the
    //     Payment, the PAID flip, the ORDER_PAID mail. A currency the order disagrees with is a
    //     recorded ORPHAN in webhook mode, never a 400 (accept_online_payment.md's follow-up).
    Reconciliation reconciliation =
        paymentService.reconcileAndCreate(
            txDsl,
            orgId,
            txn,
            new OrderRef(intent.getSalesOrderId(), null),
            CurrencyMismatch.ORPHAN);
    txn.applyReconciliation(reconciliation.status(), now);
    txnRepo.update(txn);

    if (reconciliation.order() != null && reconciliation.order().getStatus() == OrderStatus.PAID) {
      int abandoned = txnRepo.abandonOpenClaims(reconciliation.order().getId(), txn.getId(), now);
      if (abandoned > 0) {
        log.info(
            "Order {} paid by card {} — abandoned {} open InstaPay claim(s)",
            reconciliation.order().getOrderNumber(),
            txnId,
            abandoned);
      }
    }

    if (reconciliation.status() == PaymentReconciliationStatus.ORPHAN) {
      // The order left PENDING_PAYMENT before this landed (expired, cancelled, or already PAID by
      // another rail or a genuine double charge). The money is captured, visible, and in the
      // refund queue; the intent is not settled by it.
      log.warn(
          "Paymob txn {} for org {} settled by Paymob but order {} is {} — recorded ORPHAN",
          txnId,
          orgId,
          reconciliation.order() == null
              ? intent.getSalesOrderId()
              : reconciliation.order().getOrderNumber(),
          reconciliation.order() == null ? "gone" : reconciliation.order().getStatus());
      return Outcome.of(Outcome.Kind.ORPHAN, "order no longer awaiting payment");
    }

    if (intent.settle(txnId, now)) {
      intentRepo.update(intent);
    }
    log.info(
        "Paymob txn {} for org {} → {} on order {} (intent {} SETTLED)",
        txnId,
        orgId,
        reconciliation.status(),
        reconciliation.order() == null ? "-" : reconciliation.order().getOrderNumber(),
        intent.getId());
    return Outcome.of(Outcome.Kind.SETTLED, reconciliation.status().name());
  }

  private static Optional<PaymentIntent> resolveIntent(
      PaymentIntentRepository intentRepo, UUID orgId, PaymobCallback cb) {
    Optional<PaymentIntent> byReference =
        intentRepo.findBySpecialReference(orgId, cb.merchantOrderId());
    if (byReference.isPresent()) {
      return byReference;
    }
    return intentRepo.findBySpecialReference(orgId, cb.extraIntentId());
  }
}
