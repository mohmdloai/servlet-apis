# Slice 9: Invoice Void + Reissue

> Corrective lifecycle on an already-issued [SalesInvoice](../sys-analysis/system/state-machines.md#c-salesinvoice).
> An issued invoice is an immutable legal/tax document: when its frozen numbers or customer snapshot
> are wrong, the customer needs a **corrected** invoice — not just a cancelled one. This slice adds
> the only two operations that produce that outcome, plus a read endpoint (invoices previously had no
> API surface — they were born only as a side-effect of delivery).

---

## Goal

Let an admin correct a wrong invoice while it is still purely a paperwork error:

1. **Void** (`POST /api/orgs/{orgId}/invoices/{id}/void`) — cancel an `ISSUED` invoice, recording a
   `void_reason` and `voided_at`. No replacement; the fulfillment is left unbound.
2. **Reissue** (`POST /api/orgs/{orgId}/invoices/{id}/reissue`) — in one transaction, void the wrong
   invoice and issue a corrected replacement (admin-supplied lines) against the **same fulfillment**,
   with a fresh gapless number.
3. **Read** (`GET /api/orgs/{orgId}/invoices/{id}`) — the invoice + its lines.

Void/reissue require **MANAGER**; read requires **VIEWER**.

---

## Why reissue (and not just void)

Voiding answers *"should this invoice exist?"* — No. Reissue answers *"what is the **right**
document?"* — the customer still needs a valid one for their accountant / tax filing. A voided
invoice is not a *corrected* invoice, and "no invoice" is not something a tax authority accepts. The
canonical examples: a wrong VAT rate, a missed discount, a wrong billing address / legal name.

A `DELIVERED` fulfillment is terminal — its `SHIPPED → DELIVERED` issuance trigger cannot re-fire —
so the correction cannot be produced by the original path. Reissue is therefore a **second trigger**
for issuance, **not a second implementation**: it reuses the very same `InvoiceService.issueForFulfillment`
collaborator the delivery flow uses (same number-claiming, same prepayment FIFO auto-allocation,
same customer snapshot). One issuance routine, two triggers.

---

## The voidable window — why the guards are the design

Void and reissue are legal **only** when the invoice is `ISSUED`, **unpaid** (no `payment_allocation`
rows) and **uncredited** (no live `credit_note`). That window means *"the invoice is wrong but
nothing has acted on it yet."* Step outside it and the correct accounting instrument flips:

| Outside the window | Why void is wrong | Correct instrument |
|---|---|---|
| Already **paid** (allocations exist) | money settled against the document | CreditNote-backed refund (Slice 8) |
| Already **credited** (live credit notes) | a financial trail would be orphaned | reverse the credit notes first |

So the guards (`lockVoidable`) are not just safety rails — they are the line between *"correctable
paperwork"* and *"a trail that must be reversed, not erased."* The one case the system cannot detect
— the customer already filed the original with their tax authority — is accepted in v1 for an
unpaid/uncredited invoice; a stricter regime would force credit-note+reissue there too.

---

## The partial unique index (V35)

`sales_invoice.fulfillment_id` was `NOT NULL UNIQUE`. That plain constraint makes reissue impossible:
the voided row keeps occupying the fulfillment's slot, so the corrected invoice collides. V35 drops
it and adds a **partial** unique index:

```sql
CREATE UNIQUE INDEX sales_invoice_fulfillment_id_live_uq
  ON sales_invoice (fulfillment_id) WHERE status <> 'VOID';
```

"At most one **live** invoice per fulfillment; any number of VOID siblings." `findByFulfillmentId`
(which backs idempotent re-delivery) is updated to **exclude VOID**, so it always returns the live
corrected invoice — never a cancelled one — mirroring the index.

---

## Layout

- **domain** — `SalesInvoice.voidInvoice(reason, now)` (guards `ISSUED`, defensive `paidAmount == 0`);
  `SalesInvoiceRepository.updateVoidState` + `findLinesByInvoiceId`; `findByFulfillmentId` doc notes
  the VOID exclusion.
- **repository** — impl of the above; `findByFulfillmentId` `STATUS <> VOID`; V35 migration.
- **service** — `InvoiceAdminService` (transaction-owning: `get` / `voidInvoice` / `reissue`),
  reusing the `InvoiceService` collaborator for reissue's new-invoice issuance.
- **api** — `InvoiceResponse` / `VoidInvoiceRequest` / `ReissueInvoiceRequest`, `InvoiceMapper`,
  `InvoiceHandler`, `"invoices"` route in `OrgServlet`, `invoiceAdminService` in `AppConfig`.
- **test** — `InvoiceVoidReissueIT`: void happy-path + reason/allocation/credit-note/double-void
  guards; reissue void-and-replace, chaining (many VOID coexist), line/allocation guards; `get`.

---

## Out of scope (v1)

- No reissue of a **paid** or **credited** invoice — that is a credit-note concern, by design.
- No detection of "customer already filed the original" — accepted risk for an unpaid invoice.
- No per-line amend in place — invoices stay immutable; correction is always void + replace.
