# Slice P3: Portal invoices / receipts — "my invoices"

> A logged-in customer views and downloads *their* invoices. A customer-scoped read on the P1 plane,
> reusing the invoice reads and the server-side PDF renderer.
>
> Canonical: [`frontst/docs/customer-portal-epic.md`](../../frontst/docs/customer-portal-epic.md).
> Depends on **P1** (session) and the existing invoice reads + `DocumentRenderService`. Feeds frontend
> story 36.

---

## Goal
```
GET /api/portal/invoices?page=&size=     → PageResponse of the session customer's invoices (newest first)
GET /api/portal/invoices/{id}            → one invoice + lines (customer-safe), 404 if not theirs
GET /api/portal/invoices/{id}/pdf        → the rendered A4 invoice PDF (application/pdf)
```
All scoped to the session `(org_id, customer_id)`; invoice→order→customer ownership is asserted server-side.

## Why reuse
Invoices are issued as a side-effect of delivery/sale and already have staff reads
(`GET /api/orgs/{orgId}/invoices[/{id}]`) and a PDF renderer (`DocumentRenderService`, used by the staff
download + emails). The portal reuses both — the only new logic is **ownership scoping** (the customer
owns an invoice iff its `sales_order.customer_id == ctx.customerId`) and a **customer-safe DTO** (drop any
staff-only field; keep number, dates, lines, totals, status, store letterhead).

## Design
- **Repository:** an invoice read filtered/joined by `customer_id` — `findByCustomerId(orgId, customerId,
  page, size)` (invoice ⋈ sales_order on `customer_id`), lines batch-loaded; and an ownership check for
  the `/{id}` + `/pdf` paths (`invoiceBelongsToCustomer`).
- **Service:** `CustomerPortalService.listInvoices` / `getInvoice` / `renderInvoicePdf` — the last calls
  the existing `DocumentRenderService` with the same invoice model the staff path uses; ownership 404 first.
- **DTO:** a `PortalInvoiceResponse` (or reuse the invoice response minus staff-only fields) — customer-safe
  whitelist, consistent with the epic's no-leak rule.
- **API:** `PortalServlet` gains `GET /invoices[/{id}][/{id}/pdf]`. List/detail `private, no-store`; the PDF
  streams bytes (`Content-Disposition: inline; filename="INV-….pdf"`).

## Scope
**In:** the three reads + ownership scoping + customer-safe DTO + PDF stream. **Out:** credit notes /
refunds view (staff-mediated); issuing/voiding (never customer-side); receipts for in-store sales without a
customer (no `customer_id` → not listed).

## Authorization
Valid customer session; `(org_id, customer_id)`-scoped via invoice→order→customer. A foreign/unknown
invoice id → opaque 404 on detail and pdf alike.

## Acceptance criteria
1. `GET /invoices` lists exactly the session customer's invoices (newest first, paged); none → empty page.
2. `GET /invoices/{id}` returns the customer-safe invoice + lines for an owned invoice; foreign/unknown → 404.
3. `GET /invoices/{id}/pdf` streams the A4 PDF for an owned invoice (`application/pdf`); foreign → 404.
4. No staff-only field or internal id leaks (JSON scan); cross-customer isolation holds on all three.

## Tests
`PortalInvoicesIT`: AC 1–4 incl. the foreign-invoice 404 (detail + pdf), the no-leak scan, and a PDF
content-type/length assertion (mirroring the staff PDF IT). Reuses the P1 session helper and the existing
invoice fixtures.

## What this unblocks
Frontend story 36 (the invoice list + inline/download PDF in the account area).
