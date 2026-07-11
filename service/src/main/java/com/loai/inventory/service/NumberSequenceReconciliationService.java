package com.loai.inventory.service;

import com.loai.inventory.domain.repository.NumberSequenceReconciliationRepository;
import com.loai.inventory.domain.repository.NumberSequenceReconciliationRepository.CounterDrift;
import com.loai.inventory.domain.repository.NumberSequenceReconciliationRepositoryFactory;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one-time heal for an environment whose invoice / credit-note counter drifted behind its table
 * — see {@code stories/number_sequence_integrity.md} §Detect &amp; repair. Realigns each drifted
 * counter forward to {@code MAX(suffix) + 1} so a subsequent live issue stops colliding, turning
 * the 409 that {@link InvoiceService} / {@link CreditNoteService} raise from an expected failure
 * back into a legacy-data safety net.
 *
 * <p>Operator-run maintenance action, not a migration: it heals data, and whether to run it is a
 * human decision (a migration can't safely assume which side — table or counter — is authoritative
 * in an arbitrary environment). Order numbering is intentionally out of scope (see the repository
 * doc). Idempotent: forward-only, so a second run over a healed DB realigns nothing.
 */
public final class NumberSequenceReconciliationService {

  private static final Logger log =
      LoggerFactory.getLogger(NumberSequenceReconciliationService.class);

  private final DSLContext rootDsl;
  private final NumberSequenceReconciliationRepositoryFactory repoFactory;

  public NumberSequenceReconciliationService(
      DSLContext rootDsl, NumberSequenceReconciliationRepositoryFactory repoFactory) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
  }

  /**
   * One counter that was moved forward by a reconcile pass. {@code fromNextVal} is null if the
   * counter row did not exist before.
   */
  public record Realignment(
      String documentType, java.util.UUID orgId, int year, Long fromNextVal, long toNextVal) {}

  /** What a reconcile pass repaired: the realignments applied (empty when nothing had drifted). */
  public record Summary(List<Realignment> realignments) {
    public int counted() {
      return realignments.size();
    }
  }

  /**
   * Detect every drifted invoice / credit-note counter and realign it forward, all in one
   * transaction. The detect runs before the realign so the returned {@link Summary} reports exactly
   * which counters were behind and where they landed.
   */
  public Summary reconcile() {
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          NumberSequenceReconciliationRepository repo = repoFactory.create(txDsl);

          List<Realignment> realignments = new ArrayList<>();
          for (CounterDrift d : repo.detectInvoiceDrift()) {
            realignments.add(
                new Realignment(
                    "INVOICE", d.orgId(), d.year(), d.currentNextVal(), d.requiredNextVal()));
          }
          for (CounterDrift d : repo.detectCreditNoteDrift()) {
            realignments.add(
                new Realignment(
                    "CREDIT_NOTE", d.orgId(), d.year(), d.currentNextVal(), d.requiredNextVal()));
          }

          repo.realignInvoiceCounters();
          repo.realignCreditNoteCounters();

          if (realignments.isEmpty()) {
            log.info("Number-sequence reconcile: no drift found; all counters already ahead");
          } else {
            log.warn(
                "Number-sequence reconcile: realigned {} counter(s): {}",
                realignments.size(),
                realignments);
          }
          return new Summary(realignments);
        });
  }
}
