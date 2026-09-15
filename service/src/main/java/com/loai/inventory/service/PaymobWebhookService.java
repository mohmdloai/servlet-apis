package com.loai.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.common.crypto.PaymobSignature;
import com.loai.inventory.common.crypto.SecretBox;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.OrgPaymobConfig;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentIntent;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.repository.OrgPaymobConfigRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentIntentRepository;
import com.loai.inventory.domain.repository.PaymentIntentRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentRepository;
import com.loai.inventory.domain.repository.PaymentRepositoryFactory;
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
      /**
       * Verified and not acted on: pending, TOKEN, auth-only, a failed reversal — or a verified
       * body nothing can be recorded from (no {@code obj.id}, a non-positive amount). 200: a retry
       * would deliver the same body and reach the same decision.
       */
      IGNORED,
      /** Recorded, VERIFIED, reconciled against the intent's order; the order may now be PAID. */
      SETTLED,
      /** This {@code obj.id} was already recorded — idempotent replay, no further writes. */
      REPLAYED,
      /** A declined attempt: recorded as a closed row, the intent FAILED, the order untouched. */
      FAILED,
      /** Money arrived that matched no intent / the wrong amount / a closed order: ORPHAN row. */
      ORPHAN,
      /** Money left: a refund/void child recorded as a DEBIT against the original's payment. */
      REVERSED
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
  private final PaymentRepositoryFactory paymentRepoFactory;
  private final SalesOrderRepositoryFactory salesOrderRepoFactory;
  private final PaymentService paymentService;

  public PaymobWebhookService(
      DSLContext rootDsl,
      ObjectMapper mapper,
      OrgPaymobConfigRepositoryFactory configRepoFactory,
      SecretBox secretBox,
      PaymentIntentRepositoryFactory intentRepoFactory,
      PaymentTransactionRepositoryFactory txnRepoFactory,
      PaymentRepositoryFactory paymentRepoFactory,
      SalesOrderRepositoryFactory salesOrderRepoFactory,
      PaymentService paymentService) {
    this.rootDsl = rootDsl;
    this.mapper = mapper;
    this.configRepoFactory = configRepoFactory;
    this.secretBox = secretBox;
    this.intentRepoFactory = intentRepoFactory;
    this.txnRepoFactory = txnRepoFactory;
    this.paymentRepoFactory = paymentRepoFactory;
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
    // One transaction per delivery: the config that verifies the body and the rows the body writes
    // are one unit of work. Nothing in here calls out — the connection is held for a parse, an
    // HMAC and the writes, and a rejected or ignored delivery commits nothing.
    return rootDsl.transactionResult(cfg -> handle(DSL.using(cfg), orgId, rawBody, presentedHmac));
  }

  private Outcome handle(DSLContext txDsl, UUID orgId, String rawBody, String presentedHmac) {
    // 1. Which key. No config, or a disabled one, is unverifiable input: 400, not 200.
    Optional<OrgPaymobConfig> config =
        configRepoFactory.create(txDsl).findByOrgId(orgId).filter(OrgPaymobConfig::isActive);
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

    return process(txDsl, orgId, config.get(), cb, rawBody);
  }

  /**
   * The inquiry poller's door ({@code stories/paymob_card_reliability.md}): the same classification
   * and the same settlement as the webhook, for a transaction object Paymob returned to an
   * authenticated call we made ourselves over TLS — there is no signature to check and no third
   * party to impersonate anyone. A webhook and a poll that describe one transaction must be
   * indistinguishable downstream, or the two paths drift and the rare one is the buggy one; the
   * insert race on {@code (provider, provider_ref)} decides who got there first, and the loser
   * takes the idempotent-replay branch.
   */
  public Outcome settleFromInquiry(
      UUID orgId, OrgPaymobConfig config, PaymobCallback cb, String rawBody) {
    return rootDsl.transactionResult(cfg -> process(DSL.using(cfg), orgId, config, cb, rawBody));
  }

  /** Classification and settlement of one verified transaction, inside the caller's transaction. */
  private Outcome process(
      DSLContext txDsl, UUID orgId, OrgPaymobConfig config, PaymobCallback cb, String rawBody) {
    String txnId = cb.transactionId();
    if (txnId == null || txnId.isBlank()) {
      // Verified, so authentic — and unrecordable: obj.id is the provider_ref. Not REJECTED: a 400
      // would only make Paymob resend the same body. 200 ends the retries; the WARN keeps the fact.
      log.warn("Paymob webhook for org {} verified but carries no transaction id — ignored", orgId);
      return Outcome.of(Outcome.Kind.IGNORED, "transaction has no id");
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
    long amountCents = cb.amountCents();
    if (amountCents <= 0) {
      // Same shape as a missing id: authentic, nothing to record, a retry changes nothing.
      log.warn(
          "Paymob txn {} for org {} carries amount_cents {} — ignored, nothing to record",
          txnId,
          orgId,
          amountCents);
      return Outcome.of(Outcome.Kind.IGNORED, "invalid amount");
    }
    ZoneId paymobZone = PaymobHosts.zoneForRegion(config.region());

    // 6–8. Everything that writes. A refund/void child is money LEAVING and takes its own path
    //      (slice 3); a parent re-sent with is_refunded/is_voided flipped lands on the parent's
    //      dedupe below as a replay.
    if (cb.isReversal()) {
      if (!cb.success()) {
        log.warn(
            "Paymob reversal {} for org {} did not succeed — acknowledged, nothing moved",
            txnId,
            orgId);
        return Outcome.of(Outcome.Kind.IGNORED, "failed reversal");
      }
      return recordReversal(txDsl, orgId, cb, rawBody, paymobZone);
    }
    return record(txDsl, orgId, cb, rawBody, paymobZone);
  }

  /**
   * Money leaving ({@code stories/paymob_card_reliability.md}): a refund or void Paymob executed on
   * the merchant's side. Recorded as a gateway-verified DEBIT under the child's own id, linked to
   * the original through {@code raw_payload.obj.parent_transaction}; then the original's {@link
   * Payment} is reduced the way an executed refund reduces it — when that is legal. It is NOT legal
   * for money already allocated to an invoice (or a DISPUTED payment): reversing a PAID order
   * touches fulfilment, invoices and stock, and that is a decision a human makes with a
   * CreditNote-backed refund. The DEBIT row is the record either way; the order is never un-paid
   * here.
   */
  private Outcome recordReversal(
      DSLContext txDsl, UUID orgId, PaymobCallback cb, String rawBody, ZoneId paymobZone) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    PaymentTransactionRepository txnRepo = txnRepoFactory.create(txDsl);
    PaymentRepository paymentRepo = paymentRepoFactory.create(txDsl);
    String txnId = cb.transactionId();
    BigDecimal amount = BigDecimal.valueOf(cb.amountCents(), 2);
    String currency = cb.currency() == null ? "EGP" : cb.currency().strip().toUpperCase();
    String parentRef = cb.parentTransactionId();

    PaymentTransaction debit =
        PaymentTransaction.createGatewayDebit(
            UUID.randomUUID(),
            orgId,
            PaymentProvider.PAYMOB_CARD,
            txnId,
            amount,
            currency,
            PROOF,
            cb.createdAt(paymobZone),
            now);
    debit.attachAudit(rawBody);
    PaymentTransactionRepository.Recorded rec = txnRepo.insertIfAbsent(debit);
    if (!rec.inserted()) {
      log.info("Paymob reversal {} for org {} already recorded — replay", txnId, orgId);
      return Outcome.of(Outcome.Kind.REPLAYED, "already recorded");
    }

    Optional<PaymentTransaction> parent =
        parentRef == null
            ? Optional.empty()
            : txnRepo
                .findByProviderRef(PaymentProvider.PAYMOB_CARD, parentRef)
                .filter(t -> orgId.equals(t.getOrgId()));
    Optional<Payment> payment =
        parent.flatMap(t -> paymentRepo.findByTransactionId(orgId, t.getId()));
    if (payment.isEmpty()) {
      log.warn(
          "Paymob reversal {} ({} {}) for org {} names parent {} with no card payment on file —"
              + " recorded as a DEBIT only; a human matches it",
          txnId,
          amount,
          currency,
          orgId,
          parentRef);
      return Outcome.of(Outcome.Kind.REVERSED, "debit recorded; no payment to reduce");
    }
    Payment locked = paymentRepo.findByIdForUpdate(orgId, payment.get().getId()).orElseThrow();
    try {
      locked.recordRefund(amount, false, now);
      paymentRepo.updateAllocationState(locked);
      log.info(
          "Paymob reversal {} for org {}: payment {} reduced by {} {} → {} (parent txn {})",
          txnId,
          orgId,
          locked.getId(),
          amount,
          currency,
          locked.getStatus(),
          parentRef);
      return Outcome.of(Outcome.Kind.REVERSED, "payment " + locked.getStatus());
    } catch (IllegalStateException e) {
      // Allocated to an invoice, disputed, or more than what is left unallocated: the money is
      // gone on Paymob's side and the DEBIT says so; un-paying the order is the human's call.
      log.error(
          "Paymob reversal {} for org {} recorded as a DEBIT but payment {} cannot be reduced"
              + " automatically ({}); a CreditNote-backed refund is needed",
          txnId,
          orgId,
          locked.getId(),
          e.getMessage());
      return Outcome.of(Outcome.Kind.REVERSED, "debit recorded; payment needs a credit note");
    }
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
    //    signed Paymob order is not this intent's must never settle it either. The signed binding
    //    is MANDATORY: with either side absent the check would shrink, silently, to amount +
    //    currency — and identical amounts are not rare in a shop. The intention refuses to mint
    //    without Paymob's order id (JdkPaymobClient), so an intent lacking one is a row from
    //    before that rule; it gets a human, not a settlement.
    String orphanReason = null;
    if (intent == null) {
      orphanReason = "no payment intent matches this callback";
    } else if (intent.getPaymobOrderId() == null) {
      orphanReason = "intent " + intent.getId() + " carries no Paymob order id to bind against";
    } else if (cb.paymobOrderId() == null) {
      orphanReason = "callback carries no signed Paymob order id";
    } else if (!intent.getPaymobOrderId().equals(cb.paymobOrderId())) {
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
