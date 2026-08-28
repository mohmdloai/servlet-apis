package com.loai.inventory.service;

import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ClaimPendingException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentDirection;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.PaymentVerificationStatus;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentRepository;
import com.loai.inventory.domain.repository.PaymentRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentTransactionRepository;
import com.loai.inventory.domain.repository.PaymentTransactionRepository.ListFilter;
import com.loai.inventory.domain.repository.PaymentTransactionRepositoryFactory;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import com.loai.inventory.service.PaymentService.OrderRef;
import com.loai.inventory.service.PaymentService.Reconciliation;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Online-payment TX: an admin records a customer-claimed manual InstaPay transfer and verifies it
 * in one action, then reconciles it against the order.
 *
 * <p>Per the slice spec {@code stories/accept_online_payment.md}: the whole flow runs in one DB
 * transaction so the transaction-verify and the (MATCHED) payment-creation + order flip commit
 * atomically. Recording is idempotent on {@code (provider, provider_ref)} — replaying the same
 * real-world money event returns the prior outcome without creating a second payment.
 *
 * <p>Since {@code stories/payment_claim_verify.md} the shopper's claim is the unit of work: {@link
 * #claim} stores the order the shopper named and extends its hold, {@link #verifyClaim} verifies
 * that row <em>by id</em> (the reference is evidence shown to the manager, never re-typed), and the
 * free-form {@link #verify record path} refuses — 409 {@code CLAIM_PENDING} — to mint a second
 * transaction beside an open claim unless every claim is explicitly acknowledged.
 */
public final class PaymentTransactionService {

  private static final Logger log = LoggerFactory.getLogger(PaymentTransactionService.class);
  private static final String CURRENCY_EGP = "EGP";

  /**
   * How long a filed claim keeps its order open: {@code expires_at = max(current, now + 48h)}, once
   * per claim row. A shopper who paid at 23:50 on a 00:00 deadline must not lose the order and the
   * stock while the merchant sleeps. Org knobs later, beside {@code order_ttl_minutes}.
   */
  public static final Duration CLAIM_HOLD = Duration.ofHours(48);

  /** The grace a "can't find it" re-arms so the shopper can fix the reference and re-file. */
  public static final Duration NOT_FOUND_GRACE = Duration.ofHours(6);

  private final DSLContext rootDsl;
  private final PaymentTransactionRepositoryFactory txnRepoFactory;
  private final PaymentRepositoryFactory paymentRepoFactory;
  private final PaymentService paymentService;
  private final RefundService refundService;
  private final ObjectStorage storage;
  private final CustomerRepositoryFactory customerRepoFactory;
  private final UserRepositoryFactory userRepoFactory;

  public PaymentTransactionService(
      DSLContext rootDsl,
      PaymentTransactionRepositoryFactory txnRepoFactory,
      PaymentRepositoryFactory paymentRepoFactory,
      PaymentService paymentService,
      RefundService refundService,
      ObjectStorage storage,
      CustomerRepositoryFactory customerRepoFactory,
      UserRepositoryFactory userRepoFactory) {
    this.rootDsl = rootDsl;
    this.txnRepoFactory = txnRepoFactory;
    this.paymentRepoFactory = paymentRepoFactory;
    this.paymentService = paymentService;
    this.refundService = refundService;
    this.storage = storage;
    this.customerRepoFactory = customerRepoFactory;
    this.userRepoFactory = userRepoFactory;
  }

  /**
   * Admin-supplied verification command. {@code currency} defaults to EGP; {@code salesOrderId} /
   * {@code orderNumber} are both optional (absent → ORPHAN, not an error); the rest is the claim.
   * {@code acknowledgeClaimIds} is the record path's answer to the pending-claim guard: the open
   * claims on the target order the manager has looked at and decided are <em>not</em> this transfer
   * ("I checked the bank: this is a separate transfer").
   */
  public record VerifyCommand(
      PaymentProvider provider,
      String providerRef,
      BigDecimal amount,
      String currency,
      UUID salesOrderId,
      String orderNumber,
      UUID claimedByCustomerId,
      String customerNote,
      String verificationProof,
      OffsetDateTime occurredAt,
      Set<UUID> acknowledgeClaimIds) {

    public VerifyCommand {
      acknowledgeClaimIds =
          acknowledgeClaimIds == null ? Set.of() : Set.copyOf(acknowledgeClaimIds);
    }

    /** The pre-claims shape: nothing acknowledged. */
    public VerifyCommand(
        PaymentProvider provider,
        String providerRef,
        BigDecimal amount,
        String currency,
        UUID salesOrderId,
        String orderNumber,
        UUID claimedByCustomerId,
        String customerNote,
        String verificationProof,
        OffsetDateTime occurredAt) {
      this(
          provider,
          providerRef,
          amount,
          currency,
          salesOrderId,
          orderNumber,
          claimedByCustomerId,
          customerNote,
          verificationProof,
          occurredAt,
          Set.of());
    }
  }

  /**
   * The manager's "Found it" on one claim ({@link #verifyClaim}). Every field optional: {@code
   * amount} is the bank's figure when it differs from what the shopper claimed ("Different amount"
   * — never prefilled, the claim's own snapshot applies when absent); {@code occurredAt} / {@code
   * verificationProof} as on the record path.
   */
  public record VerifyClaimCommand(
      BigDecimal amount, OffsetDateTime occurredAt, String verificationProof) {}

  /** Result of a verify call: the transaction, its reconciliation, optional payment + order. */
  public record VerifyResult(
      PaymentTransaction transaction,
      PaymentReconciliationStatus reconciliationStatus,
      Payment payment,
      SalesOrder order,
      boolean replay) {}

  /**
   * A shopper-supplied payment claim (roadmap item 2). {@code salesOrderId} is the order the claim
   * pays (already resolved from a magic-link token or the portal session); {@code
   * claimedByCustomerId} stamps who filed it; {@code proofObjectKey} is an optional uploaded
   * screenshot key (validated against the order's prefix).
   */
  public record ClaimCommand(
      UUID salesOrderId,
      UUID claimedByCustomerId,
      String reference,
      String proofObjectKey,
      String note) {}

  /**
   * Result of a claim: the UNVERIFIED transaction, whether this call inserted it, and whether it
   * re-opened a NOT_FOUND claim instead (the shopper re-filed the same reference after the store
   * could not find it — "please look again"). Both false = an idempotent replay of an open claim.
   */
  public record ClaimResult(PaymentTransaction transaction, boolean inserted, boolean reopened) {

    /** The pre-phase-2 shape. */
    public ClaimResult(PaymentTransaction transaction, boolean inserted) {
      this(transaction, inserted, false);
    }
  }

  /** The reasons a manager can give for "can't find it" — the wire values of {@code reason}. */
  public static final Set<String> NOT_FOUND_REASONS =
      Set.of("NO_TRANSFER", "DIFFERENT_ACCOUNT", "OTHER");

  /**
   * Record a shopper-supplied payment claim as an {@code UNVERIFIED} CREDIT transaction that lands
   * in the staff "To verify" queue ({@code ?verification_status=UNVERIFIED}) — roadmap item 2,
   * {@code stories/shopper_payment_proof_claim.md}, reshaped by {@code
   * stories/payment_claim_verify.md}. Unlike {@link #verify}, this does <em>not</em> verify or
   * reconcile: a human still confirms, starting from the shopper's own evidence. The amount is the
   * order's <b>outstanding</b> (grand_total − prepaid) snapshotted now; a fully-paid order
   * (outstanding ≤ 0) is a 409 (before the domain's {@code amount > 0} invariant).
   *
   * <p>The row stores the order the shopper named ({@code claimed_sales_order_id}) — the fact the
   * manager used to have to re-type — and, <b>once per claim row</b>, extends the order's hold to
   * {@code max(expires_at, now + 48h)} so the sweeper cannot expire an order whose shopper says
   * they have paid. Idempotent on {@code (provider, provider_ref)}: a re-filed identical reference
   * returns the prior row ({@code inserted=false}) and extends nothing (no stacking).
   */
  public ClaimResult claim(UUID orgId, ClaimCommand cmd) {
    if (orgId == null) {
      throw new ValidationException("orgId is required");
    }
    if (cmd == null || cmd.salesOrderId() == null) {
      throw new ValidationException("salesOrderId is required");
    }
    String reference = Text.normalizeNumeric(cmd.reference());
    if (reference == null || reference.isBlank()) {
      throw new ValidationException("reference is required");
    }

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          PaymentTransactionRepository txnRepo = txnRepoFactory.create(txDsl);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

          SalesOrder order =
              paymentService
                  .findOrder(txDsl, orgId, new OrderRef(cmd.salesOrderId(), null))
                  .orElseThrow(() -> new NotFoundException("SalesOrder", cmd.salesOrderId()));

          BigDecimal outstanding = order.getGrandTotal().subtract(order.getPrepaidAmount());
          if (outstanding.signum() <= 0) {
            throw new ConflictException(
                "order " + order.getOrderNumber() + " has no outstanding balance to pay");
          }

          // Prefix-guard the uploaded key against this org+order (an anonymous shopper must not be
          // able to attach an arbitrary/cross-tenant key).
          String proofKey =
              (cmd.proofObjectKey() == null || cmd.proofObjectKey().isBlank())
                  ? null
                  : cmd.proofObjectKey().trim();
          if (proofKey != null
              && !proofKey.startsWith(ObjectStorage.paymentProofKeyPrefix(orgId, order.getId()))) {
            throw new ValidationException("proof_object_key does not belong to this order");
          }

          String currency = order.getCurrency() == null ? CURRENCY_EGP : order.getCurrency();
          PaymentTransaction claim =
              PaymentTransaction.createClaimed(
                  UUID.randomUUID(),
                  orgId,
                  PaymentProvider.INSTAPAY_MANUAL,
                  reference,
                  outstanding,
                  currency,
                  cmd.claimedByCustomerId(),
                  order.getId(),
                  Text.normalizeText(cmd.note()),
                  proofKey,
                  null, // no admin verification proof — this is the shopper's own claim
                  now,
                  now);
          PaymentTransactionRepository.Recorded rec = txnRepo.insertIfAbsent(claim);

          // The hold moves once, when the row is born — a replay of the same reference is the same
          // claim and must not stack another 48h onto the order.
          if (rec.inserted()) {
            paymentService.extendHoldForClaim(
                txDsl, orgId, order.getId(), now.plus(CLAIM_HOLD), now);
          }

          // Re-filing the reference of a NOT_FOUND claim re-opens it: "please look again". Same
          // row, same reference, no second hold (the not-found already re-armed one).
          boolean reopened = false;
          if (!rec.inserted()
              && rec.transaction().getVerificationStatus() == PaymentVerificationStatus.NOT_FOUND
              && order.getId().equals(rec.transaction().getClaimedSalesOrderId())) {
            PaymentTransaction locked =
                txnRepo.findByIdForUpdate(orgId, rec.transaction().getId()).orElse(null);
            if (locked != null
                && locked.getVerificationStatus() == PaymentVerificationStatus.NOT_FOUND) {
              locked.reopen(now);
              txnRepo.update(locked);
              rec = new PaymentTransactionRepository.Recorded(locked, false);
              reopened = true;
            }
          }
          log.info(
              "Shopper payment claim orgId={} order={} ref={} inserted={} reopened={}",
              orgId,
              order.getOrderNumber(),
              reference,
              rec.inserted(),
              reopened);
          return new ClaimResult(rec.transaction(), rec.inserted(), reopened);
        });
  }

  /**
   * "Found it" — verify one shopper claim <b>by id</b> and reconcile it against the order the
   * shopper named ({@code stories/payment_claim_verify.md}). This is the queue's verb: the manager
   * has read the reference off the screen, found the transfer in the bank app, and confirms.
   * Nothing is re-typed, so there is no second transaction to mint by mistake.
   *
   * <p>Locks the claimed order first, then the claim row ({@code findByIdForUpdate} — the {@link
   * #resolveOrphan} precedent), so a concurrent expiry sweep or a second manager serialises here.
   * Then: UNVERIFIED or NOT_FOUND → VERIFIED (the bank's {@code amount} overriding the claim's
   * snapshot when given), {@link PaymentService#reconcileAndCreate} against {@code
   * claimed_sales_order_id} — the same MATCHED / UNDERPAID / OVERPAID / ORPHAN outcomes, the same
   * PAID flip, the same ORDER_PAID mail as the record path. When the order goes PAID, every other
   * open claim on it is closed ABANDONED in the same transaction (a duplicate re-file can no longer
   * be verified against a paid order). On UNDERPAID the siblings deliberately stay open — the order
   * is still owed money and one of them may be the top-up. An order that already left
   * PENDING_PAYMENT reconciles ORPHAN with the order carried in the result so the client can say
   * why; the orphan exits apply.
   *
   * <p>Idempotent: an already-VERIFIED claim replays 200 with its payment and order (the second of
   * two managers lands on "already verified by …"). ABANDONED → 409: the order was settled by a
   * sibling or is gone; a transfer that did arrive is a plain record → orphan queue.
   */
  public VerifyResult verifyClaim(
      UUID orgId, UUID transactionId, VerifyClaimCommand cmd, UUID verifiedBy) {
    if (transactionId == null) {
      throw new ValidationException("transaction id is required");
    }
    if (verifiedBy == null) {
      throw new ValidationException("verifier identity is required");
    }
    VerifyClaimCommand c = cmd == null ? new VerifyClaimCommand(null, null, null) : cmd;
    if (c.amount() != null && c.amount().signum() <= 0) {
      throw new ValidationException("amount must be > 0");
    }

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          PaymentTransactionRepository txnRepo = txnRepoFactory.create(txDsl);
          PaymentRepository paymentRepo = paymentRepoFactory.create(txDsl);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

          // Lock order → then claim. The sweeper and the cancel path both lock the order first and
          // then close its open claims; taking the claim row first here would be an AB/BA deadlock.
          PaymentTransaction peek =
              txnRepo
                  .findById(orgId, transactionId)
                  .orElseThrow(
                      () ->
                          new NotFoundException(
                              "payment transaction " + transactionId + " not found"));
          if (peek.getClaimedSalesOrderId() != null) {
            paymentService.lockOrder(txDsl, orgId, peek.getClaimedSalesOrderId());
          }
          PaymentTransaction txn =
              txnRepo
                  .findByIdForUpdate(orgId, transactionId)
                  .orElseThrow(
                      () ->
                          new NotFoundException(
                              "payment transaction " + transactionId + " not found"));

          if (txn.getDirection() != PaymentDirection.CREDIT) {
            throw new ValidationException("only CREDIT transactions can be verified as a claim");
          }

          // Idempotent replay: already verified (by this manager a moment ago, or by a colleague).
          if (txn.getVerificationStatus() == PaymentVerificationStatus.VERIFIED) {
            Payment prior = paymentRepo.findByTransactionId(orgId, txn.getId()).orElse(null);
            UUID orderId =
                prior != null && prior.getSalesOrderId() != null
                    ? prior.getSalesOrderId()
                    : txn.getClaimedSalesOrderId();
            SalesOrder priorOrder =
                orderId == null
                    ? null
                    : paymentService
                        .findOrder(txDsl, orgId, new OrderRef(orderId, null))
                        .orElse(null);
            log.info(
                "Idempotent claim-verify replay for transaction {} (reconciliation={})",
                transactionId,
                txn.getReconciliationStatus());
            return new VerifyResult(txn, txn.getReconciliationStatus(), prior, priorOrder, true);
          }
          if (txn.getVerificationStatus() == PaymentVerificationStatus.ABANDONED) {
            throw new ConflictException(
                "claim "
                    + txn.getProviderRef()
                    + " was closed: the order was settled by another claim or is no longer"
                    + " awaiting payment. If this transfer really arrived, record it as a"
                    + " separate transfer.");
          }

          // UNVERIFIED | NOT_FOUND → VERIFIED, with the bank's figures applied first.
          txn.applyBankDetails(
              c.amount(), c.occurredAt(), Text.normalizeText(c.verificationProof()), now);
          txn.verify(verifiedBy, now);

          OrderRef ref = new OrderRef(txn.getClaimedSalesOrderId(), null);
          Reconciliation rec = paymentService.reconcileAndCreate(txDsl, orgId, txn, ref);
          txn.applyReconciliation(rec.status(), now);
          txnRepo.update(txn);

          abandonSiblingsIfSettled(txnRepo, txn, rec, now);

          log.info(
              "Verified claim {} ({}) → {} on order {} by {}",
              transactionId,
              txn.getProviderRef(),
              rec.status(),
              rec.order() == null ? "-" : rec.order().getOrderNumber(),
              verifiedBy);
          return new VerifyResult(txn, rec.status(), rec.payment(), rec.order(), false);
        });
  }

  /** Result of "can't find it": the NOT_FOUND claim, the re-armed deadline, and a replay flag. */
  public record NotFoundResult(
      PaymentTransaction transaction, SalesOrder order, OffsetDateTime heldUntil, boolean replay) {}

  /**
   * "Can't find it" — the manager searched the bank app for the claim's reference and found nothing
   * ({@code stories/payment_claim_not_found.md}). UNVERIFIED → NOT_FOUND with a {@code reason}
   * ({@link #NOT_FOUND_REASONS}) and an optional note for the shopper; the order's hold is re-armed
   * to {@code max(expires_at, now + 6h)} so the shopper has time to fix the reference and re-file;
   * the shopper is told (PAYMENT_NOT_FOUND, feed + email, magic link to the order). The reference
   * is never edited — a corrected one is a new claim, and re-filing this one re-opens it.
   *
   * <p>Lock order: the claimed order first, then the row (as {@link #verifyClaim}). Idempotent: a
   * claim already NOT_FOUND replays 200 with the answer on file (the second of two managers does
   * not re-arm the hold or re-notify). VERIFIED / ABANDONED → 409: there is nothing left to find.
   */
  public NotFoundResult markClaimNotFound(
      UUID orgId, UUID transactionId, String reason, String note, UUID actorId) {
    if (transactionId == null) {
      throw new ValidationException("transaction id is required");
    }
    if (actorId == null) {
      throw new ValidationException("actor identity is required");
    }
    String code = reason == null ? null : reason.trim().toUpperCase();
    if (code == null || !NOT_FOUND_REASONS.contains(code)) {
      throw new ValidationException("reason must be one of " + NOT_FOUND_REASONS);
    }
    String cleanNote = Text.normalizeText(note);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          PaymentTransactionRepository txnRepo = txnRepoFactory.create(txDsl);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

          PaymentTransaction peek =
              txnRepo
                  .findById(orgId, transactionId)
                  .orElseThrow(
                      () ->
                          new NotFoundException(
                              "payment transaction " + transactionId + " not found"));
          Optional<SalesOrder> lockedOrder =
              peek.getClaimedSalesOrderId() == null
                  ? Optional.empty()
                  : paymentService.lockOrder(txDsl, orgId, peek.getClaimedSalesOrderId());
          PaymentTransaction txn =
              txnRepo
                  .findByIdForUpdate(orgId, transactionId)
                  .orElseThrow(
                      () ->
                          new NotFoundException(
                              "payment transaction " + transactionId + " not found"));

          if (txn.getDirection() != PaymentDirection.CREDIT) {
            throw new ValidationException("only CREDIT transactions can be marked not found");
          }
          if (txn.getVerificationStatus() == PaymentVerificationStatus.NOT_FOUND) {
            log.info("Idempotent not-found replay for transaction {}", transactionId);
            return new NotFoundResult(
                txn,
                lockedOrder.orElse(null),
                lockedOrder.map(SalesOrder::getExpiresAt).orElse(null),
                true);
          }
          if (txn.getVerificationStatus() != PaymentVerificationStatus.UNVERIFIED) {
            throw new ConflictException(
                "claim "
                    + txn.getProviderRef()
                    + " is "
                    + txn.getVerificationStatus()
                    + "; only a pending claim can be marked not found");
          }

          txn.markNotFound(code, cleanNote, now);
          txnRepo.update(txn);

          // Re-arm the hold so the shopper can fix the reference: max(current, now + 6h).
          OffsetDateTime heldUntil = null;
          SalesOrder order = lockedOrder.orElse(null);
          if (order != null) {
            Optional<SalesOrder> held =
                paymentService.extendHoldForClaim(
                    txDsl, orgId, order.getId(), now.plus(NOT_FOUND_GRACE), now);
            if (held.isPresent()) {
              order = held.get();
              heldUntil = order.getExpiresAt();
            }
            paymentService.notifyClaimNotFound(
                txDsl, orgId, order, txn, code, cleanNote, heldUntil, now);
          }

          log.info(
              "Marked claim {} ({}) NOT_FOUND reason={} on order {} by {} (held until {})",
              transactionId,
              txn.getProviderRef(),
              code,
              order == null ? "-" : order.getOrderNumber(),
              actorId,
              heldUntil);
          return new NotFoundResult(txn, order, heldUntil, false);
        });
  }

  /**
   * The latest claim a shopper filed against an order, for the customer-facing order reads ({@code
   * payment_claim}: pending / confirmed / not found). Empty when none was ever filed. Read-only on
   * {@code rootDsl}.
   */
  public Optional<PaymentTransaction> latestClaimFor(UUID orderId) {
    return txnRepoFactory.create(rootDsl).findLatestClaimByOrder(orderId);
  }

  /**
   * Record-and-verify a claimed transfer (the free-form path: a typed reference, amount and order).
   * {@code verifiedBy} is the admin's {@code app_user.id}. Returns the verified transaction and
   * reconciliation outcome.
   *
   * <p><b>The pending-claim guard</b> ({@code stories/payment_claim_verify.md}): when this call
   * mints a <em>new</em> row (the reference matches no recorded transaction) against an order that
   * has UNVERIFIED shopper claims, it refuses with 409 {@code CLAIM_PENDING} listing them — unless
   * every one of them is in {@code acknowledge_claim_ids}, in which case the ids are written to the
   * row's {@code raw_payload} and the record proceeds. Typing the exact reference of a claim is
   * today's behaviour: the existing row is verified (no second transaction). The unmatched / orphan
   * path (no order named) is untouched.
   */
  public VerifyResult verify(UUID orgId, VerifyCommand cmd, UUID verifiedBy) {
    validate(cmd, verifiedBy);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          PaymentTransactionRepository txnRepo = txnRepoFactory.create(txDsl);
          PaymentRepository paymentRepo = paymentRepoFactory.create(txDsl);

          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          String currency = Text.normalizeCurrency(cmd.currency());

          // 1. Idempotently record the claimed transaction (UNVERIFIED).
          PaymentTransaction claim =
              PaymentTransaction.createClaimed(
                  UUID.randomUUID(),
                  orgId,
                  cmd.provider(),
                  Text.normalizeNumeric(cmd.providerRef()),
                  cmd.amount(),
                  currency,
                  cmd.claimedByCustomerId(),
                  null, // an admin's free-form record is not a shopper claim
                  Text.normalizeText(cmd.customerNote()),
                  null, // admin record path carries no shopper-uploaded proof key
                  Text.normalizeText(cmd.verificationProof()),
                  cmd.occurredAt(),
                  now);
          PaymentTransactionRepository.Recorded rec = txnRepo.insertIfAbsent(claim);
          PaymentTransaction txn = rec.transaction();
          OrderRef ref = new OrderRef(cmd.salesOrderId(), cmd.orderNumber());

          // 2. Idempotent replay: the event was recorded AND already verified on a prior call.
          //    Re-resolve the order (non-locking) from the same request ref so the replay response
          //    body matches the first call's shape — a retry must not silently drop the order.
          if (!rec.inserted()
              && txn.getVerificationStatus() == PaymentVerificationStatus.VERIFIED) {
            Payment prior = paymentRepo.findByTransactionId(orgId, txn.getId()).orElse(null);
            SalesOrder priorOrder = paymentService.findOrder(txDsl, orgId, ref).orElse(null);
            log.info(
                "Idempotent verify replay for provider_ref={} (reconciliation={})",
                txn.getProviderRef(),
                txn.getReconciliationStatus());
            return new VerifyResult(txn, txn.getReconciliationStatus(), prior, priorOrder, true);
          }
          if (!rec.inserted()
              && txn.getVerificationStatus() == PaymentVerificationStatus.ABANDONED) {
            throw new ConflictException(
                "reference "
                    + txn.getProviderRef()
                    + " belongs to a closed claim (its order was settled by another claim or is"
                    + " no longer awaiting payment)");
          }

          // 3. The guard: a NEW reference against an order that has open shopper claims.
          if (rec.inserted() && !ref.isEmpty()) {
            guardPendingClaims(txDsl, txnRepo, orgId, ref, txn, cmd.acknowledgeClaimIds());
          }

          // 4. Verify (UNVERIFIED | NOT_FOUND → VERIFIED). When the reference IS a shopper's
          //    claim, this is that claim being verified through the old door — same outcome.
          txn.verify(verifiedBy, now);

          // 5. Reconcile against the order in the same transaction.
          Reconciliation reconciliation = paymentService.reconcileAndCreate(txDsl, orgId, txn, ref);

          // 6. Stamp the reconciliation outcome and persist the transaction's new state.
          txn.applyReconciliation(reconciliation.status(), now);
          txnRepo.update(txn);

          abandonSiblingsIfSettled(txnRepo, txn, reconciliation, now);

          return new VerifyResult(
              txn,
              reconciliation.status(),
              reconciliation.payment(),
              reconciliation.order(),
              false);
        });
  }

  /**
   * The 409 {@code CLAIM_PENDING} decision. Resolves the order the request names (non-locking —
   * reconcile takes the lock a moment later) and lists its UNVERIFIED claims other than {@code txn}
   * itself. Every one must be acknowledged; the acknowledged set is then stamped onto the new row's
   * {@code raw_payload} so the ledger shows the decision was deliberate. NOT_FOUND claims do not
   * block: the manager already looked for those and found nothing.
   */
  private void guardPendingClaims(
      DSLContext txDsl,
      PaymentTransactionRepository txnRepo,
      UUID orgId,
      OrderRef ref,
      PaymentTransaction txn,
      Set<UUID> acknowledged) {
    Optional<SalesOrder> target = paymentService.findOrder(txDsl, orgId, ref);
    if (target.isEmpty()) {
      return; // order not found → reconcile will say ORPHAN, as today
    }
    List<PaymentTransaction> open =
        txnRepo.findOpenClaimsByOrder(orgId, target.get().getId()).stream()
            .filter(c -> c.getVerificationStatus() == PaymentVerificationStatus.UNVERIFIED)
            .filter(c -> !c.getId().equals(txn.getId()))
            .toList();
    if (open.isEmpty()) {
      return;
    }
    Set<UUID> missing = new HashSet<>();
    for (PaymentTransaction c : open) {
      if (!acknowledged.contains(c.getId())) {
        missing.add(c.getId());
      }
    }
    if (!missing.isEmpty()) {
      throw new ClaimPendingException(
          "order "
              + target.get().getOrderNumber()
              + " has "
              + open.size()
              + " pending payment claim(s); verify the claim instead, or acknowledge every"
              + " claim id to record this as a separate transfer",
          open.stream()
              .map(
                  c ->
                      new ClaimPendingException.PendingClaim(
                          c.getId(),
                          c.getProviderRef(),
                          c.getAmount(),
                          c.getRecordedAt(),
                          c.getProofObjectKey() != null))
              .toList());
    }
    String ids =
        open.stream().map(c -> "\"" + c.getId() + "\"").collect(Collectors.joining(",", "[", "]"));
    txn.attachAudit("{\"acknowledged_claim_ids\":" + ids + "}");
    log.info(
        "Recording {} against order {} with {} open claim(s) acknowledged",
        txn.getProviderRef(),
        target.get().getOrderNumber(),
        open.size());
  }

  /**
   * When a verification settles the order (MATCHED / OVERPAID → PAID), every other open claim on it
   * can no longer be true, so they close ABANDONED in the same transaction. UNDERPAID leaves them
   * open on purpose (see {@link #verifyClaim}); ORPHAN means the order was not payable and the
   * sweeper / cancel already closed its claims.
   */
  private static void abandonSiblingsIfSettled(
      PaymentTransactionRepository txnRepo,
      PaymentTransaction txn,
      Reconciliation rec,
      OffsetDateTime now) {
    if (rec.order() == null || rec.order().getStatus() != OrderStatus.PAID) {
      return;
    }
    int abandoned = txnRepo.abandonOpenClaims(rec.order().getId(), txn.getId(), now);
    if (abandoned > 0) {
      log.info(
          "Order {} paid by {} — abandoned {} other open claim(s)",
          rec.order().getOrderNumber(),
          txn.getProviderRef(),
          abandoned);
    }
  }

  /**
   * Resolve an ORPHAN transaction by matching it to an admin-chosen order, reusing the same
   * reconcile / prepaid / SO→PAID path as the automated online flow. This is the manual override
   * the docs require before launch ({@code state-machines.md}: "manual InstaPay will produce
   * orphans on day 1"); authorization (MANAGER+) is enforced at the servlet.
   *
   * <p>The transaction must be a VERIFIED CREDIT currently in ORPHAN. The chosen {@code ref} is fed
   * to {@link PaymentService#reconcileAndCreate}: on an exact cover ({@code amount == grand_total −
   * prepaid}) this creates the {@link Payment} and flips the order to PAID, and the transaction
   * moves ORPHAN → MATCHED. Any non-MATCHED outcome (order not found, order not PENDING_PAYMENT,
   * under/overpaid) persists nothing and is reported as a 4xx so the transaction stays in the
   * orphan queue — partial / overpaid manual matches are intentionally not supported in this slice.
   *
   * <p>Idempotent: re-resolving a transaction that already has a Payment returns that payment
   * (replay) instead of erroring, mirroring {@link #verify}.
   */
  public VerifyResult resolveOrphan(UUID orgId, UUID transactionId, OrderRef ref, UUID resolvedBy) {
    if (transactionId == null) {
      throw new ValidationException("transaction id is required");
    }
    if (resolvedBy == null) {
      throw new ValidationException("resolver identity is required");
    }
    if (ref == null || ref.isEmpty()) {
      throw new ValidationException("sales_order_id or order_number is required");
    }

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          PaymentTransactionRepository txnRepo = txnRepoFactory.create(txDsl);
          PaymentRepository paymentRepo = paymentRepoFactory.create(txDsl);

          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

          PaymentTransaction txn =
              txnRepo
                  .findByIdForUpdate(orgId, transactionId)
                  .orElseThrow(
                      () ->
                          new NotFoundException(
                              "payment transaction " + transactionId + " not found"));

          // Idempotent replay: a Payment already exists for this txn → it was resolved before.
          // Return it with the order it is actually attached to (not the request ref).
          Optional<Payment> prior = paymentRepo.findByTransactionId(orgId, transactionId);
          if (prior.isPresent()) {
            Payment payment = prior.get();
            SalesOrder priorOrder =
                payment.getSalesOrderId() == null
                    ? null
                    : paymentService
                        .findOrder(txDsl, orgId, new OrderRef(payment.getSalesOrderId(), null))
                        .orElse(null);
            log.info(
                "Idempotent orphan-resolve replay for transaction {} (reconciliation={})",
                transactionId,
                txn.getReconciliationStatus());
            return new VerifyResult(txn, txn.getReconciliationStatus(), payment, priorOrder, true);
          }

          // Only a VERIFIED, CREDIT, still-ORPHAN transaction can be matched to an order.
          if (txn.getDirection() != PaymentDirection.CREDIT) {
            throw new ValidationException("only CREDIT transactions can be matched to an order");
          }
          if (txn.getVerificationStatus() != PaymentVerificationStatus.VERIFIED) {
            throw new ConflictException(
                "transaction is "
                    + txn.getVerificationStatus()
                    + "; only a VERIFIED transaction can be resolved");
          }
          if (txn.getReconciliationStatus() != PaymentReconciliationStatus.ORPHAN) {
            throw new ConflictException(
                "transaction reconciliation is "
                    + txn.getReconciliationStatus()
                    + "; only an ORPHAN transaction can be resolved");
          }

          Reconciliation rec = paymentService.reconcileAndCreate(txDsl, orgId, txn, ref);

          if (rec.status() != PaymentReconciliationStatus.MATCHED) {
            // Throw so the whole txn rolls back (an OVERPAID reconcile writes a Payment and flips
            // the order — the rollback discards that) and the transaction stays ORPHAN in the
            // queue. Manual matches accept only an exact cover in this slice.
            throw orphanRejection(rec, txn);
          }

          txn.applyReconciliation(rec.status(), now);
          txnRepo.update(txn);

          abandonSiblingsIfSettled(txnRepo, txn, rec, now);

          log.info(
              "Resolved ORPHAN transaction {} → MATCHED on order {} (payment {}) by {}",
              transactionId,
              rec.order().getOrderNumber(),
              rec.payment().getId(),
              resolvedBy);
          return new VerifyResult(txn, rec.status(), rec.payment(), rec.order(), false);
        });
  }

  /**
   * Result of refunding an unmatched orphan: the transaction, its standalone payment, the refund.
   */
  public record OrphanRefundResult(
      PaymentTransaction transaction, Payment payment, Refund refund, boolean replay) {}

  /**
   * The refund exit from the orphan queue ({@code state-machines.md} E: the orphan's two
   * resolutions are "link manually" — {@link #resolveOrphan} — and "refund"). For a VERIFIED CREDIT
   * transaction that genuinely matches no order (wrong reference, duplicate payment, payment for an
   * expired order), the admin chooses to send the money back: per {@code refund.md} §"Orphan
   * transaction", this promotes the transaction into a standalone {@link Payment} ({@code
   * sales_order_id} NULL, {@code unallocated_amount} = full amount — the V23 "orphan/unmatched"
   * path) and creates a <b>PENDING</b> direct refund for the full amount against it, atomically. No
   * money moves here — the admin performs the real reverse transfer and executes the refund
   * separately (two-step lifecycle, {@code RefundService#execute}).
   *
   * <p>The transaction's {@code reconciliation_status} intentionally stays ORPHAN — it never
   * matched an order, and that history is the truth. Its <b>disposition</b> marker is the 1:1
   * Payment now bound to it (the same marker {@link #resolveOrphan} uses for replay): orphan-queue
   * queries exclude transactions that already have a payment. From here on the money's lifecycle
   * lives on the Payment/Refund aggregates — if this refund is later cancelled, re-invoking this
   * method (or {@code POST /refunds} directly against the payment) opens a fresh PENDING refund.
   *
   * <p>{@code method} defaults to the transaction's own provider (the money goes back the way it
   * came); the OWNER approval threshold applies exactly as on any other direct refund ({@code
   * callerIsOwnerOrAdmin}). Idempotent: a replay returns the existing open refund.
   */
  public OrphanRefundResult refundOrphan(
      UUID orgId,
      UUID transactionId,
      PaymentProvider method,
      String notes,
      UUID actorId,
      boolean callerIsOwnerOrAdmin) {
    if (transactionId == null) {
      throw new ValidationException("transaction id is required");
    }
    if (actorId == null) {
      throw new ValidationException("actor identity is required");
    }

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          PaymentTransactionRepository txnRepo = txnRepoFactory.create(txDsl);
          PaymentRepository paymentRepo = paymentRepoFactory.create(txDsl);

          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

          PaymentTransaction txn =
              txnRepo
                  .findByIdForUpdate(orgId, transactionId)
                  .orElseThrow(
                      () ->
                          new NotFoundException(
                              "payment transaction " + transactionId + " not found"));

          // A Payment already bound to this transaction means it was dispositioned before.
          Optional<Payment> existing = paymentRepo.findByTransactionId(orgId, transactionId);
          if (existing.isPresent()) {
            Payment payment = existing.get();
            if (payment.getSalesOrderId() != null) {
              throw new ConflictException(
                  "transaction "
                      + transactionId
                      + " was matched to order "
                      + payment.getSalesOrderId()
                      + "; refund that order's money via cancellation or a credit note instead");
            }
            // Standalone payment from a prior refundOrphan. Open refund → idempotent replay.
            Optional<Refund> open =
                refundService.findOpenDirectByPaymentInTx(txDsl, orgId, payment.getId());
            if (open.isPresent()) {
              log.info(
                  "Idempotent orphan-refund replay for transaction {} (refund {})",
                  transactionId,
                  open.get().getId());
              return new OrphanRefundResult(txn, payment, open.get(), true);
            }
            // Every prior refund was cancelled — re-open a fresh PENDING one on the same payment.
            Payment locked =
                paymentRepo
                    .findByIdForUpdate(orgId, payment.getId())
                    .orElseThrow(() -> new NotFoundException("Payment", payment.getId()));
            Refund reopened =
                refundService.createDirectPendingInTx(
                    txDsl,
                    orgId,
                    locked,
                    locked.getUnallocatedAmount(),
                    method != null ? method : txn.getProvider(),
                    orphanRefundNotes(notes),
                    callerIsOwnerOrAdmin,
                    now);
            return new OrphanRefundResult(txn, locked, reopened, false);
          }

          // First disposition: only a VERIFIED, CREDIT, still-ORPHAN transaction can be refunded.
          if (txn.getDirection() != PaymentDirection.CREDIT) {
            throw new ValidationException("only CREDIT transactions can be refunded");
          }
          if (txn.getVerificationStatus() != PaymentVerificationStatus.VERIFIED) {
            throw new ConflictException(
                "transaction is "
                    + txn.getVerificationStatus()
                    + "; only a VERIFIED transaction can be refunded");
          }
          if (txn.getReconciliationStatus() != PaymentReconciliationStatus.ORPHAN) {
            throw new ConflictException(
                "transaction reconciliation is "
                    + txn.getReconciliationStatus()
                    + "; only an ORPHAN transaction can be refunded this way");
          }

          Payment payment =
              Payment.createUnmatched(
                  UUID.randomUUID(),
                  orgId,
                  txn.getClaimedByCustomerId(),
                  txn.getId(),
                  txn.getAmount(),
                  txn.getCurrency(),
                  now);
          paymentRepo.insert(payment);

          Refund refund =
              refundService.createDirectPendingInTx(
                  txDsl,
                  orgId,
                  payment,
                  payment.getUnallocatedAmount(),
                  method != null ? method : txn.getProvider(),
                  orphanRefundNotes(notes),
                  callerIsOwnerOrAdmin,
                  now);

          log.info(
              "Refunding ORPHAN transaction {} → standalone payment {} + PENDING refund {} "
                  + "(amount {}, by {})",
              transactionId,
              payment.getId(),
              refund.getId(),
              refund.getAmount(),
              actorId);
          return new OrphanRefundResult(txn, payment, refund, false);
        });
  }

  /**
   * One page of the transaction ledger/queues plus the filtered total (for tab badges), with the
   * rows' claim context batch-loaded — {@code customers} keyed by {@code claimed_by_customer_id},
   * {@code claimedOrders} by {@code claimed_sales_order_id} — two queries per page, never one per
   * row. Rows that are not shopper claims simply have no entry.
   */
  public record TransactionPage(
      List<PaymentTransaction> items,
      long total,
      Map<UUID, Customer> customers,
      Map<UUID, SalesOrder> claimedOrders) {

    /** The pre-claims shape: no context. */
    public TransactionPage(List<PaymentTransaction> items, long total) {
      this(items, total, Map.of(), Map.of());
    }
  }

  /**
   * A transaction with its money context: the 1:1 payment and that payment's order, if any, plus
   * {@code proofUrl} — a short-lived presigned GET for the screenshot the shopper attached to their
   * claim, or null when they attached none (or the row predates the feature) — plus its claim
   * context: the shopper who filed it, the order they named, and the name of whoever verified it.
   *
   * <p>The proof is deliberately on the <b>detail</b> only. Presigning every row of a 100-item
   * worklist page would mint 100 credentials the operator will not look at, and a URL that grants
   * anyone holding it read access has no business sitting in a cached list response.
   */
  public record TransactionDetail(
      PaymentTransaction transaction,
      Payment payment,
      SalesOrder order,
      String proofUrl,
      Customer customer,
      SalesOrder claimedOrder,
      String verifiedByName) {

    /** The pre-claims shape: no claim context. */
    public TransactionDetail(
        PaymentTransaction transaction, Payment payment, SalesOrder order, String proofUrl) {
      this(transaction, payment, order, proofUrl, null, null, null);
    }
  }

  /** The claim context of one transaction, for the verify response — the detail's three reads. */
  public record ClaimContext(Customer customer, SalesOrder claimedOrder, String verifiedByName) {}

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  /**
   * Read one page of the org's transactions ({@code stories/list_payment_transactions.md}). The
   * filter is mechanical — the <b>open orphan queue</b> is the caller's composition {@code
   * reconciliation_status=ORPHAN + has_payment=false} ({@code transaction.md} §Operational
   * queries); no queue semantics are added here. {@code page} floors at 0, {@code size} is clamped
   * to {@code [1, MAX_PAGE_SIZE]}. The claims queue ({@code ?verification_status=UNVERIFIED}) comes
   * back ordered by the claimed order's clock ({@link ListFilter#isClaimsQueue}) and, like every
   * page, with its customers and claimed orders batch-loaded.
   */
  public TransactionPage list(UUID orgId, ListFilter filter, int page, int size) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    PaymentTransactionRepository repo = txnRepoFactory.create(rootDsl);
    List<PaymentTransaction> items = repo.list(orgId, filter, p * s, s);
    long total = repo.count(orgId, filter);

    Set<UUID> customerIds =
        items.stream()
            .map(PaymentTransaction::getClaimedByCustomerId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Set<UUID> orderIds =
        items.stream()
            .map(PaymentTransaction::getClaimedSalesOrderId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<UUID, Customer> customers =
        customerRepoFactory.create(rootDsl).findByIds(orgId, customerIds);
    Map<UUID, SalesOrder> orders = paymentService.findOrders(rootDsl, orgId, orderIds);
    return new TransactionPage(items, total, customers, orders);
  }

  /**
   * Read one transaction with its disposition context: the 1:1 payment bound to it (matched at
   * verify time, resolved, or refund-dispositioned) and, when that payment is order-linked, the
   * order — the detail view's answer to "how was this handled?" — plus a presigned view of the
   * shopper's uploaded proof, so the operator deciding whether to verify can actually look at the
   * evidence the shopper sent. Collecting a screenshot nobody can open is worse than not asking for
   * one. Since {@code stories/payment_claim_verify.md} also the claim context: who filed it, which
   * order they named, and who verified it.
   */
  public TransactionDetail get(UUID orgId, UUID transactionId) {
    if (transactionId == null) {
      throw new ValidationException("transaction id is required");
    }
    PaymentTransaction txn =
        txnRepoFactory
            .create(rootDsl)
            .findById(orgId, transactionId)
            .orElseThrow(
                () -> new NotFoundException("payment transaction " + transactionId + " not found"));
    Payment payment =
        paymentRepoFactory.create(rootDsl).findByTransactionId(orgId, transactionId).orElse(null);
    SalesOrder order =
        payment == null || payment.getSalesOrderId() == null
            ? null
            : paymentService
                .findOrder(rootDsl, orgId, new OrderRef(payment.getSalesOrderId(), null))
                .orElse(null);
    ClaimContext ctx = contextOf(orgId, txn);
    return new TransactionDetail(
        txn,
        payment,
        order,
        presignProof(txn),
        ctx.customer(),
        ctx.claimedOrder(),
        ctx.verifiedByName());
  }

  /**
   * The claim context of one transaction (three point reads on {@code rootDsl}): the shopper who
   * filed it, the order they named, and the verifier's display name. Used by the detail read and by
   * the verify response, so the client that just pressed "Found it" learns who did.
   */
  public ClaimContext contextOf(UUID orgId, PaymentTransaction txn) {
    Customer customer =
        txn.getClaimedByCustomerId() == null
            ? null
            : customerRepoFactory
                .create(rootDsl)
                .findById(orgId, txn.getClaimedByCustomerId())
                .orElse(null);
    SalesOrder claimedOrder =
        txn.getClaimedSalesOrderId() == null
            ? null
            : paymentService
                .findOrder(rootDsl, orgId, new OrderRef(txn.getClaimedSalesOrderId(), null))
                .orElse(null);
    String verifiedByName =
        txn.getVerifiedBy() == null
            ? null
            : userRepoFactory
                .create(rootDsl)
                .findById(txn.getVerifiedBy())
                .map(PaymentTransactionService::displayNameOf)
                .orElse(null);
    return new ClaimContext(customer, claimedOrder, verifiedByName);
  }

  private static String displayNameOf(AppUser u) {
    return u.getDisplayName() == null || u.getDisplayName().isBlank()
        ? u.getEmail()
        : u.getDisplayName();
  }

  /**
   * Presign the claim's screenshot for viewing, or null when there is none. The stored key is
   * re-checked against this org's payment-proof prefix before it is signed: the write path already
   * enforces that prefix, and this is the read path refusing to mint a credential for anything
   * outside it even if a bad key ever reached the column.
   */
  private String presignProof(PaymentTransaction txn) {
    String key = txn.getProofObjectKey();
    if (key == null || key.isBlank()) {
      return null;
    }
    if (!key.startsWith(ObjectStorage.paymentProofOrgPrefix(txn.getOrgId()))) {
      log.warn(
          "Refusing to presign proof key outside org {} on transaction {}",
          txn.getOrgId(),
          txn.getId());
      return null;
    }
    return storage.presignGet(key);
  }

  private static String orphanRefundNotes(String notes) {
    String t = Text.normalizeText(notes);
    return t != null ? t : "orphan refund — verified transfer with no matching order";
  }

  /** Precise 4xx for a manual match that {@code reconcileAndCreate} did not turn into a MATCHED. */
  private static AppException orphanRejection(Reconciliation rec, PaymentTransaction txn) {
    SalesOrder order = rec.order();
    return switch (rec.status()) {
      case ORPHAN ->
          order == null
              ? new NotFoundException("no order found for the given reference in this org")
              : new ConflictException(
                  "order "
                      + order.getOrderNumber()
                      + " is "
                      + order.getStatus()
                      + "; only a PENDING_PAYMENT order can accept a matched payment");
      case UNDERPAID ->
          new ConflictException(
              "amount "
                  + txn.getAmount()
                  + " is less than the order's outstanding "
                  + outstanding(order)
                  + "; partial orphan matches are not supported");
      case OVERPAID ->
          new ConflictException(
              "amount "
                  + txn.getAmount()
                  + " exceeds the order's outstanding "
                  + outstanding(order)
                  + "; overpaid orphan matches are not supported");
      default -> new ConflictException("cannot resolve orphan (outcome " + rec.status() + ")");
    };
  }

  private static BigDecimal outstanding(SalesOrder order) {
    return order.getGrandTotal().subtract(order.getPrepaidAmount());
  }

  private void validate(VerifyCommand cmd, UUID verifiedBy) {
    if (cmd == null) {
      throw new ValidationException("request body is required");
    }
    if (verifiedBy == null) {
      throw new ValidationException("verifier identity is required");
    }
    if (cmd.provider() == null) {
      throw new ValidationException("provider is required");
    }
    if (cmd.providerRef() == null || cmd.providerRef().isBlank()) {
      throw new ValidationException("provider_ref is required");
    }
    if (cmd.amount() == null) {
      throw new ValidationException("amount is required");
    }
    if (cmd.amount().signum() <= 0) {
      throw new ValidationException("amount must be > 0");
    }
  }
}
