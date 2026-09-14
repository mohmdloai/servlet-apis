package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.PaymentIntent;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link PaymentIntent} (V99). Bound to a transactional {@code DSLContext} via
 * {@link PaymentIntentRepositoryFactory} — every method runs in the caller's context.
 */
public interface PaymentIntentRepository {

  void insert(PaymentIntent intent);

  Optional<PaymentIntent> findById(UUID orgId, UUID id);

  /** The webhook's one lookup: Paymob echoes our {@code special_reference}; find its intent. */
  Optional<PaymentIntent> findBySpecialReference(UUID orgId, String specialReference);

  /**
   * The newest still-live ({@code PENDING}, {@code expires_at > now}) intent for this order, at
   * exactly this amount and currency — what a second tap on "pay" reuses instead of minting a
   * second intention. Empty when the amount changed or every prior attempt is spent.
   */
  Optional<PaymentIntent> findLiveForOrder(
      UUID orgId, UUID salesOrderId, BigDecimal amount, String currency, OffsetDateTime now);

  /** Persist the mutable columns: Paymob's handles, status, attribution, {@code updated_at}. */
  void update(PaymentIntent intent);

  /**
   * The inquiry poller's work list ({@code stories/paymob_card_reliability.md}): {@code PENDING}
   * intents created before {@code cutoff} (the grace window — sweeping an intent seconds after
   * creation races the shopper still typing and the webhook itself), oldest first, at most {@code
   * limit}. Served by {@code idx_payment_intent_pending}.
   *
   * <p><b>Deliberately un-scoped by org</b>, like {@code
   * SalesOrderRepository.findExpiredPendingIds}: the sweeper is a platform job that walks every
   * tenant's backlog; every row still carries its {@code org_id} and every write downstream is
   * org-scoped through it.
   */
  List<PaymentIntent> findPendingCreatedBefore(OffsetDateTime cutoff, int limit);

  /**
   * Intents stuck {@code PENDING} past their own {@code expires_at} — usually an abandoned
   * checkout, occasionally a misconfigured integration, and after slice 3 always something the
   * poller could not resolve. A count, not a queue ({@code OrgHealth.cardIntentsStuck}).
   */
  long countStuck(UUID orgId, OffsetDateTime now);

  /**
   * Retire every live ({@code PENDING}) intent of an org — on a Paymob reconnect or disconnect,
   * because each one's checkout secret and Paymob-side order belong to the credentials and
   * integration that just changed. A settlement arriving for one afterwards still settles (money
   * moved); what this prevents is {@code POST …/pay} handing back a checkout URL minted for the old
   * integration.
   *
   * @return how many were retired
   */
  int expireLiveForOrg(UUID orgId, OffsetDateTime now);
}
