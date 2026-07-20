package com.loai.inventory.service;

import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.CreditNote;
import com.loai.inventory.domain.model.CreditNoteLine;
import com.loai.inventory.domain.model.CreditNoteReason;
import com.loai.inventory.domain.model.CreditNoteStatus;
import com.loai.inventory.domain.model.InvoiceStatus;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.repository.CreditNoteRepository;
import com.loai.inventory.domain.repository.CreditNoteRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.RefundRepository;
import com.loai.inventory.domain.repository.RefundRepositoryFactory;
import com.loai.inventory.domain.repository.SalesInvoiceRepository;
import com.loai.inventory.domain.repository.SalesInvoiceRepositoryFactory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Issue and void {@link CreditNote}s — the negative-side mirror of {@code InvoiceService}. Owns its
 * transaction boundary via {@code rootDsl.transactionResult(...)}. See {@code
 * sys-analysis/outbound/refund.md}.
 *
 * <p>A CreditNote always credits a specific previously-billed {@link SalesInvoice}; its total may
 * not exceed that invoice's grand total. Issuance claims a gapless per-org per-year {@code
 * CN-YYYY-NNNN} number and is the authorization point for CreditNote-backed refunds — above the
 * org's {@code refund_approval_threshold} it requires an OWNER (the caller passes whether they hold
 * OWNER / system ADMIN).
 */
public final class CreditNoteService {

  private static final Logger log = LoggerFactory.getLogger(CreditNoteService.class);

  /**
   * Postgres-named unique index behind {@code UNIQUE (org_id, credit_note_number)} on credit_note.
   */
  private static final String CREDIT_NOTE_NUMBER_CONSTRAINT =
      "credit_note_org_id_credit_note_number_key";

  private final DSLContext rootDsl;
  private final CreditNoteRepositoryFactory creditNoteRepoFactory;
  private final SalesInvoiceRepositoryFactory invoiceRepoFactory;
  private final RefundRepositoryFactory refundRepoFactory;
  private final OrgRepositoryFactory orgRepoFactory;

  public CreditNoteService(
      DSLContext rootDsl,
      CreditNoteRepositoryFactory creditNoteRepoFactory,
      SalesInvoiceRepositoryFactory invoiceRepoFactory,
      RefundRepositoryFactory refundRepoFactory,
      OrgRepositoryFactory orgRepoFactory) {
    this.rootDsl = rootDsl;
    this.creditNoteRepoFactory = creditNoteRepoFactory;
    this.invoiceRepoFactory = invoiceRepoFactory;
    this.refundRepoFactory = refundRepoFactory;
    this.orgRepoFactory = orgRepoFactory;
  }

  /** One credit-note line to issue. */
  public record LineSpec(
      UUID productId, String description, int quantity, BigDecimal unitPrice, BigDecimal taxRate) {}

  /** Admin-supplied issuance command. */
  public record IssueCommand(
      UUID salesInvoiceId, CreditNoteReason reason, String reasonNote, List<LineSpec> lines) {}

  /** The issued credit note with its lines. */
  public record Issued(CreditNote creditNote, List<CreditNoteLine> lines) {}

  /**
   * Issue a CreditNote against an ISSUED/PAID invoice. {@code callerIsOwnerOrAdmin} gates the
   * above-threshold escalation. Runs in its own transaction.
   */
  public Issued issue(UUID orgId, IssueCommand cmd, boolean callerIsOwnerOrAdmin) {
    validate(cmd);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          CreditNoteRepository creditNoteRepo = creditNoteRepoFactory.create(txDsl);
          SalesInvoiceRepository invoiceRepo = invoiceRepoFactory.create(txDsl);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

          // Lock the invoice row for the rest of the txn so concurrent issuances against it
          // serialize — the cumulative-credit cap below then sees every committed sibling note.
          SalesInvoice invoice =
              invoiceRepo
                  .findByIdForUpdate(orgId, cmd.salesInvoiceId())
                  .orElseThrow(() -> new NotFoundException("SalesInvoice", cmd.salesInvoiceId()));
          if (invoice.getStatus() != InvoiceStatus.ISSUED
              && invoice.getStatus() != InvoiceStatus.PAID) {
            throw new ConflictException(
                "cannot credit invoice "
                    + invoice.getInvoiceNumber()
                    + " in status "
                    + invoice.getStatus()
                    + "; must be ISSUED or PAID");
          }

          UUID creditNoteId = UUID.randomUUID();
          List<CreditNoteLine> lines = new ArrayList<>(cmd.lines().size());
          BigDecimal subtotal = BigDecimal.ZERO;
          BigDecimal taxTotal = BigDecimal.ZERO;
          for (LineSpec spec : cmd.lines()) {
            CreditNoteLine line =
                CreditNoteLine.create(
                    UUID.randomUUID(),
                    creditNoteId,
                    spec.productId(),
                    spec.description(),
                    spec.quantity(),
                    spec.unitPrice(),
                    spec.taxRate());
            lines.add(line);
            subtotal = subtotal.add(line.getLineSubtotal());
            taxTotal = taxTotal.add(line.getLineTax());
          }
          BigDecimal total = subtotal.add(taxTotal);

          // Credit notes for an invoice may not cumulatively exceed what it billed (gross grand
          // total). The invoice row lock above serializes concurrent issuances, so this sum sees
          // every committed sibling note — two issuances can't both slip past a stale total.
          BigDecimal alreadyCredited =
              creditNoteRepo.sumIssuedTotalByInvoice(orgId, invoice.getId());
          BigDecimal creditedWithThis = alreadyCredited.add(total);
          if (creditedWithThis.compareTo(invoice.getGrandTotal()) > 0) {
            throw new ValidationException(
                "credit notes for invoice "
                    + invoice.getInvoiceNumber()
                    + " would total "
                    + creditedWithThis
                    + " (already "
                    + alreadyCredited
                    + " + this "
                    + total
                    + "), exceeding invoice grand total "
                    + invoice.getGrandTotal());
          }

          // Above-threshold escalation: returning this much money requires an OWNER.
          BigDecimal threshold = orgThreshold(txDsl, orgId);
          if (total.compareTo(threshold) > 0 && !callerIsOwnerOrAdmin) {
            throw new AuthorizationException(
                "credit note total "
                    + total
                    + " exceeds approval threshold "
                    + threshold
                    + "; requires OWNER");
          }

          CreditNote note =
              CreditNote.createDraft(
                  creditNoteId,
                  orgId,
                  invoice.getCustomerId(),
                  invoice.getId(),
                  cmd.reason(),
                  Text.normalizeText(cmd.reasonNote()),
                  subtotal,
                  taxTotal,
                  invoice.getCurrency(),
                  now);

          int year = now.getYear();
          // The allocator is the single owner of the number: it both claims the gapless sequence
          // and formats CN-YYYY-NNNN. This service never constructs a credit-note number itself.
          String creditNoteNumber = creditNoteRepo.claimCreditNoteNumber(orgId, year);
          note.issue(creditNoteNumber, now);
          try {
            creditNoteRepo.insert(note, lines);
          } catch (DataAccessException e) {
            // Same drift hazard as invoices: if credit_note_number_counter trails the credit_note
            // table, the minted number is already taken and the (org_id, credit_note_number) unique
            // index rejects the insert. Map it to a 409 that names the remedy, not an "Unexpected
            // error" 500. Narrowed to the number constraint; any other violation still bubbles.
            if (NumberSequenceConflicts.isUniqueViolationOn(e, CREDIT_NOTE_NUMBER_CONSTRAINT)) {
              throw new ConflictException(
                  "Credit-note number sequence is out of sync — contact support.");
            }
            throw e;
          }

          log.info(
              "Issued credit note {} (id={}) orgId={} invoice={} reason={} total={}",
              note.getCreditNoteNumber(),
              creditNoteId,
              orgId,
              invoice.getInvoiceNumber(),
              cmd.reason(),
              total);
          return new Issued(note, lines);
        });
  }

  /**
   * A credit note's detail read: the note, its lines, and {@code refundedTotal} — the sum of
   * EXECUTED refunds against it. {@code remaining = note.total − refundedTotal} is what a client
   * can still refund against the note; exposing both here means the UI never has to reconstruct it
   * by scanning the refund ledger.
   */
  public record Detail(
      CreditNote creditNote, List<CreditNoteLine> lines, BigDecimal refundedTotal) {}

  /** Read a credit note (with lines and its EXECUTED-refund total) for the GET endpoint. */
  public Detail get(UUID orgId, UUID id) {
    CreditNoteRepository repo = creditNoteRepoFactory.create(rootDsl);
    CreditNote note =
        repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("CreditNote", id));
    BigDecimal refundedTotal = refundRepoFactory.create(rootDsl).sumExecutedByCreditNote(orgId, id);
    return new Detail(note, repo.findLinesByCreditNoteId(id), refundedTotal);
  }

  /**
   * An invoice's crediting story: the invoice, the cap guard's own already-credited sum ({@code
   * sumIssuedTotalByInvoice} — ISSUED + SETTLED, VOID excluded, regardless of the {@code status}
   * filter), and its credit notes oldest-first. Backs {@code GET /credit-notes?sales_invoice_id=}
   * so a client can render "X of Y already credited" from the same numbers {@link #issue} enforces,
   * instead of discovering the cap by a 400.
   */
  public record InvoiceCreditNotes(
      SalesInvoice invoice,
      BigDecimal creditedTotal,
      List<CreditNote> notes,
      Map<UUID, BigDecimal> refundedTotals) {}

  /**
   * List the credit notes raised against one invoice, optionally filtered by {@code status} (VOID
   * included when unfiltered — voided notes are part of the story). No pagination: the count is
   * bounded by the cumulative-credit cap. Notes carry no lines — the detail read ({@code GET
   * /credit-notes/{id}}) has them.
   *
   * @throws NotFoundException if the invoice is not in {@code orgId}
   */
  public InvoiceCreditNotes listForInvoice(
      UUID orgId, UUID salesInvoiceId, CreditNoteStatus status) {
    if (salesInvoiceId == null) {
      throw new ValidationException("sales_invoice_id is required");
    }
    SalesInvoice invoice =
        invoiceRepoFactory
            .create(rootDsl)
            .findById(orgId, salesInvoiceId)
            .orElseThrow(() -> new NotFoundException("SalesInvoice", salesInvoiceId));
    CreditNoteRepository repo = creditNoteRepoFactory.create(rootDsl);
    List<CreditNote> notes = repo.findByInvoiceId(orgId, salesInvoiceId, status);
    Map<UUID, BigDecimal> refundedTotals =
        refundRepoFactory
            .create(rootDsl)
            .sumExecutedByCreditNotes(orgId, notes.stream().map(CreditNote::getId).toList());
    return new InvoiceCreditNotes(
        invoice, repo.sumIssuedTotalByInvoice(orgId, salesInvoiceId), notes, refundedTotals);
  }

  /** Void an ISSUED CreditNote — rejected if any refund has been EXECUTED against it. */
  public CreditNote voidNote(UUID orgId, UUID id) {
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          CreditNoteRepository creditNoteRepo = creditNoteRepoFactory.create(txDsl);
          RefundRepository refundRepo = refundRepoFactory.create(txDsl);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

          CreditNote note =
              creditNoteRepo
                  .findByIdForUpdate(orgId, id)
                  .orElseThrow(() -> new NotFoundException("CreditNote", id));
          if (refundRepo.existsExecutedByCreditNote(orgId, id)) {
            throw new ConflictException(
                "cannot void credit note " + id + ": a refund has been executed against it");
          }
          note.voidNote(now);
          creditNoteRepo.updateStatus(note);
          log.info("Voided credit note {} orgId={}", id, orgId);
          return note;
        });
  }

  private BigDecimal orgThreshold(DSLContext txDsl, UUID orgId) {
    Org org =
        orgRepoFactory
            .create(txDsl)
            .findById(orgId)
            .orElseThrow(() -> new NotFoundException("Org", orgId));
    return org.getRefundApprovalThreshold();
  }

  private void validate(IssueCommand cmd) {
    if (cmd == null) {
      throw new ValidationException("request body is required");
    }
    if (cmd.salesInvoiceId() == null) {
      throw new ValidationException("sales_invoice_id is required");
    }
    if (cmd.reason() == null) {
      throw new ValidationException("reason is required");
    }
    if (cmd.lines() == null || cmd.lines().isEmpty()) {
      throw new ValidationException("at least one line is required");
    }
    // Validate each line here so bad input is a 400, not a 500 from CreditNoteLine.create's
    // IllegalArgumentException (which the servlet's generic catch maps to Internal server error).
    for (int i = 0; i < cmd.lines().size(); i++) {
      LineSpec line = cmd.lines().get(i);
      if (line == null) {
        throw new ValidationException("lines[" + i + "] is required");
      }
      if (line.description() == null) {
        throw new ValidationException("lines[" + i + "].description is required");
      }
      if (line.unitPrice() == null) {
        throw new ValidationException("lines[" + i + "].unit_price is required");
      }
      if (line.taxRate() == null) {
        throw new ValidationException("lines[" + i + "].tax_rate is required");
      }
      if (line.quantity() <= 0) {
        throw new ValidationException("lines[" + i + "].quantity must be > 0");
      }
      if (line.unitPrice().signum() < 0) {
        throw new ValidationException("lines[" + i + "].unit_price must be >= 0");
      }
      if (line.taxRate().signum() < 0) {
        throw new ValidationException("lines[" + i + "].tax_rate must be >= 0");
      }
    }
  }
}
