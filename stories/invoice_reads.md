# Slice: Invoice-side reads (the awaiting-payment worklist / invoice ledger)

> The invoice aggregate was write-and-detail only: an invoice is issued as a side-effect of
> delivery / in-store sale, then reachable one of two ways — `GET /invoices/{id}` (needs the id,
> which the client only had from a cached deliver-response) or `GET /sales-orders/{id}/invoices`
> (needs the order). There was **no org-wide list**: the "which invoices are awaiting payment"
> worklist the Invoice-management screen (`docs/frontend-architecture.md` §1, appendix) presupposes
> could not be rendered at all — the same gap `stories/money_reads.md` closed for refunds. This
> slice adds the missing cross-order list. Frontend driver: the Invoice management worklist.

---

## Goal

An org member reads the org's invoices as a worklist / ledger:

`GET /api/orgs/{orgId}/invoices?status=&page=&size=` — filtered by `status` it is a worklist
(`?status=ISSUED` = the awaiting-payment queue, oldest first); unfiltered it is the ledger (every
status incl. VOID, newest first). Each row is **lean** — enough to render a card without a per-row
fetch: the number, the money meter (`grand_total` / `paid_amount` for a partial-paid hint), the
frozen customer-snapshot name, and the batch-loaded `sales_order_number`. Lines are omitted (the
`GET /invoices/{id}` detail carries those).

Done means: the Invoice-management screen can render "awaiting / paid / void / all" segments and
each card can say `INV-2026-0007 · SO-2026-000123 · EGP 120.00 · 40 of 120 paid` without one extra
request per row.

---

## Semantics

- **Queue vs ledger** (same convention as `GET /refunds`, `GET /payment-transactions`,
  `GET /fulfillments`): a `status` filter makes it a worklist — `created_at ASC, id ASC` (chase the
  oldest awaiting-payment first); no filter makes it the audit ledger — `created_at DESC, id DESC`,
  every status **including VOID** (a reissue reads as voided predecessor + replacement). `page`
  floors at 0, `size` clamps to `[1, 100]` (default 20), `PageResponse` envelope. Unknown
  `status` → 400 (`DRAFT`/`ISSUED`/`PAID`/`VOID`, case-insensitive).
- **Lean rows.** The row is the invoice header, not the full document — no lines. Everything a card
  needs already rides on `sales_invoice`: `grand_total`, `paid_amount` (the partial-paid meter),
  `currency`, the frozen `customer_name` snapshot, `status`, `issued_at`, `voided_at`, `created_at`.
  Only `sales_order_number` is not on the row — it is **batch-loaded**, one
  `SalesOrderRepository.findOrderNumbersByIds` per page, never per row (no N+1).
- **Bare `GET /invoices` is the ledger**, not a 400 — same as `GET /refunds` (and unlike
  `GET /credit-notes` / `GET /sales-orders`, whose bare form 400s because they demand a required
  filter). There is nothing required here; no filter simply means "the whole ledger".
- Reads are read-only on `rootDsl`, no lock; org-scoping is invisibility (a foreign invoice never
  appears), the shared-schema convention.
- **No `POST /invoices`.** Invoices are issued only as a side-effect of delivery / in-store sale;
  `POST /invoices` (and every non-GET at the collection root) is a 405, unchanged.

### Service

- `InvoiceAdminService.list(orgId, status, page, size)` → `InvoicePage(List<InvoiceSummary>,
  total)`; `InvoiceSummary` = invoice header + batch-loaded `salesOrderNumber`. `page`/`size`
  clamped in the service too, so the envelope echoes what was served.
- `SalesInvoiceRepository.list(orgId, status, offset, limit)` + `count(orgId, status)` — the
  queue-vs-ledger ordering, mirroring `RefundRepository.list`/`count`.

---

## API contract

```
GET /api/orgs/{orgId}/invoices?status=ISSUED&page=0&size=20
```

`200 OK` (`PageResponse` envelope; each row an `InvoiceSummaryResponse`, lines omitted):
```json
{
  "data": [
    {
      "id": "<uuid>",
      "invoice_number": "INV-2026-0007",
      "status": "ISSUED",
      "grand_total": 120.00,
      "paid_amount": 40.00,
      "currency": "EGP",
      "customer_name": "Nadia",
      "sales_order_id": "<uuid>",
      "sales_order_number": "SO-2026-000123",
      "fulfillment_id": "<uuid>",
      "issued_at": "2026-07-01T10:00:00Z",
      "created_at": "2026-07-01T10:00:00Z"
    }
  ],
  "total": 1,
  "page": 0,
  "size": 20
}
```
(A partial-paid card reads `40 of 120 paid`; a VOID row carries `voided_at` and appears only in the
unfiltered ledger or under `?status=VOID`.)

### Errors
| Status | Cause |
|---|---|
| `400` | unknown `status`; non-integer `page`/`size` |
| `403` | caller has no role in `:orgId` |
| `405` | any non-GET at the collection root (no `POST /invoices`) |

---

## Authorization

`AuthzHelper.requireOrgAccess(orgId, VIEWER)` — the project-wide read bar. The existing
detail/void/reissue gates (VIEWER read, MANAGER mutate) are unchanged.

---

## Tests

- **`InvoiceReadsIT`** (service against real jOOQ): the ISSUED queue is oldest-first while the
  ledger is newest-first and VOID appears only in the ledger / under `?status=VOID`; page/size
  clamp (not error); rows carry the batch-loaded `sales_order_number` and the money meter; org
  scoping.
- **`InvoiceHandlerAuthTest`** (handler at runtime): `GET /invoices` allowed for VIEWER; an unknown
  `status` is a 400 with the service never called; `POST /invoices` at the root is a 405.

---

## Out of scope / known gaps

- **G2 — reissue per-line validation** (fixed alongside, non-blocking): `POST /invoices/{id}/reissue`
  had no per-line guard, so a non-positive quantity / missing or negative unit price / negative tax
  reached `SalesInvoiceLine.create`'s `IllegalArgumentException` (or a DB CHECK) and surfaced as a
  **500**. `InvoiceAdminService.reissue` now validates each line first (400 `lines[i].*`, same shape
  as `CreditNoteService`), before the void — so a rejected reissue leaves the original ISSUED
  invoice untouched. Covered by `InvoiceVoidReissueIT#reissue_rejectsBadLines_beforeVoiding`.
- No text search / date-range filter on the list (the money worklists don't have them either); add
  when a screen needs it.
- Zero new dependencies; no migration (the read is over existing columns).
