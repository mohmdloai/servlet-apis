package com.loai.inventory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.common.crypto.PaymobSignature;
import com.loai.inventory.common.crypto.SecretBox;
import com.loai.inventory.common.exception.UpstreamFailureException;
import com.loai.inventory.domain.model.OrgPaymobConfig;
import com.loai.inventory.domain.model.PaymentIntent;
import com.loai.inventory.domain.repository.OrgPaymobConfigRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentIntentRepository;
import com.loai.inventory.domain.repository.PaymentIntentRepositoryFactory;
import com.loai.inventory.service.PaymobWebhookService.Outcome;
import com.loai.inventory.service.paymob.PaymobCallback;
import com.loai.inventory.service.paymob.PaymobClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Card payments that survive a dropped webhook ({@code stories/paymob_card_reliability.md}): the
 * poller behind the {@code paymob-inquiry} JobRunr job. It walks {@code PENDING} intents older than
 * the grace window and asks Paymob what became of each one; a settled transaction is settled
 * through {@link PaymobWebhookService#settleFromInquiry} — the identical path the webhook takes — a
 * failed one fails the intent, and an intent past its deadline with no transaction at all is
 * retired.
 *
 * <p><b>The grace window matters.</b> Sweeping an intent seconds after creation races the shopper
 * still typing their card number, and — worse — races the webhook itself. Three minutes (the
 * default) is long enough that a normal payment has resolved one way or the other, and short enough
 * that a shopper who refreshes their order page sees PAID before they open a support chat.
 *
 * <p><b>Failure isolation, two grades.</b> One bad intent must not stop the other ninety-nine: a
 * per-intent failure is logged and the loop continues (the poison-pill rule the order sweeper wrote
 * down). Paymob being unreachable is different — it is down for everyone, so the batch ends and
 * every intent stays exactly as it was, to be retried next interval. Like {@link
 * OrderExpiryService#sweep}, {@link #sweep} itself opens no transaction: each write is its own.
 */
public final class PaymobInquiryService {

  private static final Logger log = LoggerFactory.getLogger(PaymobInquiryService.class);

  private final DSLContext rootDsl;
  private final ObjectMapper mapper;
  private final PaymentIntentRepositoryFactory intentRepoFactory;
  private final OrgPaymobConfigRepositoryFactory configRepoFactory;
  private final SecretBox secretBox;
  private final PaymobClient paymob;
  private final PaymobWebhookService settlement;
  private final Duration grace;

  public PaymobInquiryService(
      DSLContext rootDsl,
      ObjectMapper mapper,
      PaymentIntentRepositoryFactory intentRepoFactory,
      OrgPaymobConfigRepositoryFactory configRepoFactory,
      SecretBox secretBox,
      PaymobClient paymob,
      PaymobWebhookService settlement,
      Duration grace) {
    this.rootDsl = rootDsl;
    this.mapper = mapper;
    this.intentRepoFactory = intentRepoFactory;
    this.configRepoFactory = configRepoFactory;
    this.secretBox = secretBox;
    this.paymob = paymob;
    this.settlement = settlement;
    this.grace = grace;
  }

  /** What one sweep did. {@code aborted} = Paymob stopped answering and the batch ended early. */
  public record Summary(
      int scanned,
      int settled,
      int failed,
      int expired,
      int replayed,
      int leftPending,
      int orphaned,
      int reversed,
      int errors,
      boolean aborted) {}

  /**
   * One pass over the backlog. <b>Opens no transaction</b> — the candidate read is autocommit and
   * every write is its own short transaction, so nothing here can roll back a sibling's outcome.
   */
  public Summary sweep(int batchLimit) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime cutoff = now.minus(grace);
    List<PaymentIntent> due =
        intentRepoFactory.create(rootDsl).findPendingCreatedBefore(cutoff, batchLimit);

    Map<UUID, List<PaymentIntent>> byOrg = new LinkedHashMap<>();
    for (PaymentIntent intent : due) {
      byOrg.computeIfAbsent(intent.getOrgId(), k -> new java.util.ArrayList<>()).add(intent);
    }

    int settled = 0;
    int failed = 0;
    int expired = 0;
    int replayed = 0;
    int leftPending = 0;
    int orphaned = 0;
    int reversed = 0;
    int errors = 0;
    boolean aborted = false;

    orgs:
    for (Map.Entry<UUID, List<PaymentIntent>> entry : byOrg.entrySet()) {
      UUID orgId = entry.getKey();
      List<PaymentIntent> intents = entry.getValue();

      Optional<OrgPaymobConfig> found =
          configRepoFactory.create(rootDsl).findByOrgId(orgId).filter(OrgPaymobConfig::isActive);
      if (found.isEmpty()) {
        // The merchant disconnected (or paused) with checkouts in flight. Nothing can be asked;
        // the intents are retired so they stop being a work item. A callback that still arrives
        // for one is a 400 at the webhook — the one window a merchant opens by disconnecting.
        for (PaymentIntent intent : intents) {
          if (expire(intent, now, "org has no active Paymob configuration")) {
            expired++;
          }
        }
        continue;
      }
      OrgPaymobConfig config = found.get();

      if (!config.canInquire()) {
        // Connected before V100: no API key on file, so Paymob cannot be asked. Intents wait for
        // their own deadline and then expire, loudly — a lost webhook here needs manual matching
        // until the org reconnects with its API key.
        for (PaymentIntent intent : intents) {
          if (!intent.getExpiresAt().isAfter(now)) {
            if (expire(
                intent,
                now,
                "org has no Paymob API key on file — cannot inquire; reconnect with api_key")) {
              expired++;
            }
          } else {
            leftPending++;
          }
        }
        continue;
      }

      String token;
      try {
        token = paymob.authenticate(config, secretBox.decrypt(config.apiKeyEncrypted()));
      } catch (UpstreamFailureException e) {
        log.warn(
            "Paymob inquiry sweep: auth-token exchange failed for org {} — ending the batch, {}"
                + " intent(s) stay PENDING ({})",
            orgId,
            intents.size(),
            e.getMessage());
        aborted = true;
        break;
      }
      String hmacSecret = null;

      for (PaymentIntent intent : intents) {
        try {
          Optional<String> transaction =
              paymob.inquireTransaction(
                  config, token, intent.getPaymobOrderId(), intent.getSpecialReference());
          if (transaction.isEmpty()) {
            if (!intent.getExpiresAt().isAfter(now)) {
              if (expire(intent, now, "no Paymob transaction by the intent's deadline")) {
                expired++;
              }
            } else {
              leftPending++;
            }
            continue;
          }

          JsonNode txnNode = mapper.readTree(transaction.get());
          String body = PaymobCallback.envelopeForInquiry(mapper, txnNode);
          PaymobCallback cb = PaymobCallback.parse(mapper, body);

          // Verified the same way where a signature is present; otherwise trusted as an
          // authenticated API response over TLS — we made the call, with the merchant's key.
          if (cb.embeddedHmac() != null) {
            if (hmacSecret == null) {
              hmacSecret = secretBox.decrypt(config.hmacSecretEncrypted());
            }
            if (!PaymobSignature.verify(cb.signedValues(), hmacSecret, cb.embeddedHmac())) {
              log.error(
                  "Paymob inquiry for intent {} (org {}) returned txn {} whose embedded signature"
                      + " does not verify — not settled",
                  intent.getId(),
                  orgId,
                  cb.transactionId());
              errors++;
              continue;
            }
          }

          Outcome outcome = settlement.settleFromInquiry(orgId, config, cb, body);
          switch (outcome.kind()) {
            case SETTLED -> settled++;
            case FAILED -> failed++;
            case REPLAYED -> replayed++;
            case ORPHAN -> orphaned++;
            case REVERSED -> reversed++;
            // A 3DS challenge still in flight, auth-only, or an object nothing can be recorded
            // from (no id, non-positive amount): the intent waits for its own deadline.
            case IGNORED -> leftPending++;
            case REJECTED -> {
              log.warn(
                  "Paymob inquiry for intent {} (org {}) rejected: {}",
                  intent.getId(),
                  orgId,
                  outcome.detail());
              errors++;
            }
          }
          log.info(
              "Paymob inquiry: intent {} (org {}) → {} ({})",
              intent.getId(),
              orgId,
              outcome.kind(),
              outcome.detail());
        } catch (UpstreamFailureException e) {
          log.warn(
              "Paymob inquiry sweep: provider unreachable at intent {} (org {}) — ending the"
                  + " batch; intents stay PENDING ({})",
              intent.getId(),
              orgId,
              e.getMessage());
          aborted = true;
          break orgs;
        } catch (RuntimeException | java.io.IOException e) {
          // Load-bearing catch: isolate the failure to this intent so the loop continues. The
          // intent is unchanged and will be re-found next tick. Do NOT narrow or remove — the
          // poison-pill rule (stories/expire_pending_orders.md).
          log.warn(
              "Paymob inquiry failed for intent {} (org {}) — skipping; will retry next tick",
              intent.getId(),
              orgId,
              e);
          errors++;
        }
      }
    }

    Summary summary =
        new Summary(
            due.size(),
            settled,
            failed,
            expired,
            replayed,
            leftPending,
            orphaned,
            reversed,
            errors,
            aborted);
    log.info("Paymob inquiry sweep complete: {}", summary);
    return summary;
  }

  /** Retire one intent in its own transaction, re-read under the write so a race cannot regress. */
  private boolean expire(PaymentIntent stale, OffsetDateTime now, String why) {
    boolean changed =
        rootDsl.transactionResult(
            cfg -> {
              PaymentIntentRepository repo = intentRepoFactory.create(DSL.using(cfg));
              Optional<PaymentIntent> fresh = repo.findById(stale.getOrgId(), stale.getId());
              if (fresh.isEmpty() || !fresh.get().expire(now)) {
                return false;
              }
              repo.update(fresh.get());
              return true;
            });
    if (changed) {
      log.warn(
          "Paymob inquiry: intent {} (org {}, order {}) EXPIRED — {}",
          stale.getId(),
          stale.getOrgId(),
          stale.getSalesOrderId(),
          why);
    }
    return changed;
  }
}
