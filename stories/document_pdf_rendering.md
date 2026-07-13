# Slice: Printable documents — invoice / credit-note PDF, 80mm receipt, invoice-by-email

> The finance aggregates are **JSON-only**. An invoice, a credit note, and an in-store sale each
> carry every field a real document needs — frozen customer snapshot, line snapshots, subtotal /
> tax / grand total, gapless `INV-YYYY-NNNN` / `CN-YYYY-NNNN` — but there is **no rendered artifact**:
> nothing a customer can keep, an operator can hand across the counter, or the system can attach to
> an email. A printable invoice PDF (and an 80mm receipt for the counter) is the baseline expectation
> for anything that calls itself invoicing. This slice adds a single server-side document renderer
> and the three read surfaces over it, then wires the invoice PDF onto a customer email.
>
> Grounded in: `stories/deliver_issue_invoice.md` (invoice issuance + fields),
> `stories/credit_note_refund.md` (credit-note fields), `stories/in_store_sale.md` (the counter
> sale + tender/change), `stories/invoice_reads.md` (`GET /invoices/{id}` detail read),
> `stories/deliver_customer_email_notifications.md` (the `EmailSender` outbox pipeline + the
> reserved `VIEW_INVOICE` magic-token purpose), `stories/notify_order_paid.md` (why **not**
> ORDER_PAID — see §"The trigger correction").

---

## Goal

One server-side rendering service that turns an already-frozen finance aggregate into a PDF, exposed
three ways and reused for email:

1. `GET /api/orgs/{orgId}/invoices/{id}/pdf` — a full-page (A4) printable **invoice** (VIEWER).
2. `GET /api/orgs/{orgId}/credit-notes/{id}/pdf` — a full-page (A4) printable **credit note** (VIEWER).
3. `GET /api/orgs/{orgId}/sales-orders/{id}/receipt.pdf` — an **80mm thermal receipt** for the
   in-store sale (VIEWER), carrying tender + change.
4. *(Deferred — Part C, see below)* The invoice PDF attached to a customer email at issuance.

Done means: the same authoritative bytes render whether an operator downloads an invoice from the
admin app or the cashier reprints a receipt — **one renderer, one layout, no drift**. A PDF never
restates the money; it renders the frozen snapshot the finance aggregate already holds.

> **Implementation status (2026-07-12):** Parts **A** and **B** are implemented. Part **C**
> (invoice-by-email) is **deferred** — see §"Part C (deferred)". Rationale: Part C's real value is
> for **online** orders, which have no customer-facing origin yet (no storefront). It pairs
> naturally with the storefront build, not with this admin-facing slice. Parts A+B stand alone and
> complete the in-store / admin document flow that is live today.

---

## Why one renderer, server-side (the load-bearing decision)

The tempting shortcut is to render the invoice in the browser (`@react-pdf`, print-CSS). We do **not**,
because the email attachment (leg 4) is produced by the backend delivery sweeper — there is no browser
in that path. A client renderer would force a **second** layout that must stay byte-for-byte
consistent with the emailed one; the first tax-rounding or address-wrap difference between them is a
support ticket ("my printed invoice differs from the emailed one"). So the **canonical document is
Java-rendered**, and the admin app simply downloads/prints those bytes (the frontend does zero
document layout — see `frontst/stories/23_st_document_pdf.md`). This mirrors the money rule: one
source of truth, rendered once.

### Rendering engine — `OpenPDF` (programmatic PDF; **as built**)

- **Choice (v1, implemented):** `com.github.librepdf:openpdf` (LGPL/MPL). Programmatic layout
  (`Document` / `PdfPTable` / `Paragraph`), rendered with the **built-in base-14 fonts** (Helvetica) —
  **zero font assets, zero network, deterministic**, which is exactly what a reliable first cut needs.
  Latin/English (the only `ENABLED_LOCALES` member) renders out of the box. `PageSize.A4` for
  invoice/credit-note; a custom `Rectangle(80mm)` for the thermal receipt.
- **Why not the HTML engine (yet):** `openhtmltopdf` (Apache-2.0, HTML/CSS→PDF, bidi/complex-script)
  is the documented **upgrade path** for richer layout and Arabic shaping, but it needs a **bundled
  embedded font** to render deterministically (its one real setup cost). Since v1 is English-only and
  we don't want a font binary in the repo yet, OpenPDF's built-in fonts win for now. When bilingual
  documents land (with the storefront / RTL re-enable), swap the render backend to openhtmltopdf +
  bundled Noto Naskh Arabic behind the same `DocumentRenderService` seam — callers don't change.
- **Rejected:** iText 7 (AGPL — a licensing trap for a client platform); raw PDFBox (no table/layout
  helpers).
- **Dependency wiring:** version property in the parent `pom.xml` `<properties>`, entry in the parent
  `<dependencyManagement>`, then a plain `<dependency>` in **service** — the exact pattern Angus Mail
  / JobRunr / the AWS SDK BOM already follow. Net-new to the classpath (grep confirmed **no**
  PDF/HTML-templating lib existed).

---

## The trigger correction — invoice-by-email is **INVOICE_ISSUED, not ORDER_PAID**

The feature request names ORDER_PAID as the invoice email's home. **It can't be**, and the code says
so: for an ONLINE order the flip to PAID happens in `PaymentService.reconcile` (`notify_order_paid.md`),
but the invoice is only issued later, at `DELIVERED` (`deliver_issue_invoice.md` — the DELIVERED txn is
what builds `SalesInvoice`). **At ORDER_PAID there is no invoice to attach.** `deliver_customer_email_
notifications.md` §Out already anticipated exactly this: *"`VIEW_INVOICE` — no invoice exists at
`ORDER_PLACED`; add with the invoice-email events."*

So the invoice PDF rides a **new customer email fired at issuance**:

- **Online:** produced inside the DELIVERED txn (`InvoiceService.issueForFulfillment`), where the
  invoice is born. Recipient = the order's customer, email channel. Skipped silently when the order
  has no customer email (PHONE / walk-in), same guard `ORDER_PAID` uses.
- **In-store:** the customer is at the counter and takes a **printed 80mm receipt** (leg 3); the
  in-store issuance path does **not** email (walk-ins usually have no email, and it would double the
  counter action) — mirroring `notify_order_paid.md`'s deliberate in-store silence. If an in-store
  customer supplied an email, emailing is a follow-up, not this slice.

This keeps the promise the request was reaching for — "the customer gets their invoice by email" —
attached to the moment the invoice actually exists.

---

## Scope

### Part A — Org billing profile (prerequisite; its own PR)

A professional document header needs a seller identity the `org` row does not have. Today `org` is
`{id, name, slug, active, refund_approval_threshold, order_ttl_minutes, suspended_*}` — **no address,
no tax id, no logo** (confirmed against `V15__Add_org_and_multitenancy.sql` + later ALTERs and
`domain/.../model/Org.java`).

- **Migration `V51__Add_org_billing_profile.sql`** (renumber to the next free V-number at
  implementation time; V50 is the latest): add **nullable** columns to `org` —
  `legal_name VARCHAR(255)`, `tax_registration_number VARCHAR(64)`, `address_line1 VARCHAR(255)`,
  `address_line2 VARCHAR(255)`, `city VARCHAR(128)`, `country VARCHAR(128)`, `phone VARCHAR(32)`,
  `contact_email VARCHAR(255)`, `logo_object_key VARCHAR(512)`. All nullable — an org with none set
  still renders a valid document (header falls back to `org.name`, other fields omitted). Regen jOOQ.
- **Domain/DTO/repo:** thread the fields through `Org`, `OrgRepositoryImpl`, and the org response DTO.
- **Read/write surface:** the fields are returned by `GET /api/orgs/{orgId}` and editable by the org's
  OWNER via the existing `PUT /api/orgs/{orgId}` (extend the request body; unknown/omitted fields left
  unchanged, mirroring the existing policy-knob merge), and by platform ADMIN via `PATCH /api/admin/
  orgs/{orgId}`. Validation: trim/blank→null, length caps, `contact_email` shape-checked when present.
- **Logo:** stored as an object-storage key, reusing the product-listing image infra (S3 presign /
  MinIO). `POST /api/orgs/{orgId}/logo/presign` → presigned PUT + key; `PUT /api/orgs/{orgId}`
  attaches `logo_object_key`. The renderer fetches the logo bytes at render time and inlines them as a
  `data:` URI (openhtmltopdf embeds it). Logo is **optional**; absent → no logo block. (Presign/logo
  upload MAY be deferred to a follow-up PR — the text fields alone make the document professional.)

### Part B — Document renderer + PDF endpoints (the core)

- **`DocumentRenderService`** (service module): three methods —
  `renderInvoice(orgId, invoiceId) → byte[]`, `renderCreditNote(orgId, creditNoteId) → byte[]`,
  `renderReceipt(orgId, salesOrderId) → byte[]`. Each: load the aggregate via the **existing** detail
  read (no new query) + the org billing profile, build an HTML string from a template, run it through
  openhtmltopdf. Money is formatted from the frozen decimal fields exactly as stored — **the renderer
  computes nothing.**
  - Invoice source: `InvoiceAdminService.get(orgId, id)` (header + lines + frozen customer snapshot;
    `stories/invoice_reads.md`). 404 → the handler maps `NotFoundException` to 404 as usual.
  - Credit-note source: `CreditNoteService.get(orgId, id)` (`Detail` with lines).
  - Receipt source: the order's invoice + its payment(s) + the change refund — `SalesOrder`
    (CLOSED/PAID, IN_STORE) + its `SalesInvoice` + the `Payment` (tender = payment amount) + the
    EXECUTED cash **change** refund (`in_store_sale.md` §Overpaid). Reprint reconstructs tender/change
    from these persisted rows, not from the one-shot sale response.
- **Templates** (classpath HTML + inline CSS, one per doc type): `invoice.html`, `credit-note.html`,
  `receipt-80mm.html`. A2/A4 for the first two (`@page { size: A4 }`); `@page { size: 80mm auto }` +
  monospace-ish narrow layout for the receipt. Placeholders filled by a tiny, dependency-free token
  substitution (no template engine added) or plain Java string building — the email templates set the
  precedent.
- **Binary response path** (net-new — every handler today writes JSON via a `writeJson` helper; grep
  found **no** `application/pdf` / `Content-Disposition` precedent). Add a `writePdf(resp, bytes,
  filename, disposition)` helper: `Content-Type: application/pdf`, `Content-Length`, `Content-
  Disposition: inline; filename="INV-2026-0007.pdf"` (inline so the browser previews; the frontend
  forces download when it wants). Filename derives from the document number.
- **Handlers/routes** — sub-routes on the existing dispatchers, VIEWER-gated, org-scoped:
  - `InvoiceHandler` → `GET /invoices/{id}/pdf`
  - `CreditNoteHandler` → `GET /credit-notes/{id}/pdf`
  - the sales-order dispatch → `GET /sales-orders/{id}/receipt.pdf`
  - all follow `AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER)` — a document read is a read,
    same bar as the JSON detail it renders. Non-GET on these paths → 405.

### Part C (DEFERRED) — Attach the invoice PDF to the INVOICE_ISSUED email (rides B)

> **Not implemented in this slice.** Documented here as the designed next step; it ships with the
> storefront work (online orders are where a customer invoice email earns its keep). The §"trigger
> correction" above is the load-bearing design fact that survives the deferral: the email is
> `INVOICE_ISSUED`, **never** `ORDER_PAID`.

- **`NotificationType.INVOICE_ISSUED`** (open `notification.type` TEXT column → no migration for the
  type itself) + a `NotificationTemplates` case (title `Invoice INV-… for order SO-…`, body + the
  order/invoice context + a `VIEW_INVOICE` magic link + the standard unsubscribe footer).
- **Producer:** one `NotificationService.notify(txDsl, orgId, customer(...), INVOICE_ISSUED, payload,
  refType=INVOICE, refId=invoiceId, link)` call inside `InvoiceService.issueForFulfillment` — the
  invoice-issuance txn shared by online DELIVERED and in-store. Fires **only** when the order has a
  customer email; the in-store path passes a flag to suppress (receipt-instead). Rolled-back issuance
  ⇒ no notification (produced in the same txn, same guarantee as `ORDER_PLACED`).
- **`VIEW_INVOICE` magic link:** the token purpose is **already** in the `customer_magic_token` CHECK
  (`purpose IN ('VIEW_ORDER','VIEW_INVOICE','UNSUBSCRIBE')`, `V45`) — no migration. Add
  `MagicLinkService.issueInvoiceViewLink(txDsl, orgId, customerId, invoiceId, now)` and an anonymous
  `GET /api/public/invoices/{token}` (mounted under the `/api/public/` JwtAuthFilter bypass) resolving
  the token → that one invoice's JSON (the same `InvoiceResponse` the authed detail returns) **and**
  a `GET /api/public/invoices/{token}/pdf` for the customer to re-download the PDF login-free.
- **Attachment plumbing** (extends the Phase-2 email pipeline):
  - `EmailMessage` record gains an optional attachment — e.g. `List<Attachment>` where
    `Attachment(String filename, String contentType, byte[] bytes)`. Existing callers pass an empty
    list (backward compatible).
  - `SmtpEmailSender.send` builds a `MimeMultipart` when attachments are present (HTML `MimeBodyPart`
    + one `application/pdf` `MimeBodyPart` per attachment); the no-attachment path stays a single
    `setContent(html, ...)`. `LoggingEmailSender` logs attachment filenames.
  - The email delivery subtype row (`notification_delivery_email`) records an **attachment reference**
    (`attachment_ref_type = INVOICE`, `attachment_ref_id = invoiceId`) — **not** the bytes. Migration
    `V52__Add_email_attachment_ref.sql` adds the two nullable columns.
  - **Render at send-time, not produce-time:** `dispatchPendingEmail` (the sweeper) resolves the
    attachment ref → `DocumentRenderService.renderInvoice(...)` → attaches. Rationale: keep the
    business txn light (PDF render is CPU work), and re-render on at-least-once retry is deterministic
    (the invoice snapshot is frozen). The HTML body stays frozen at produce-time as today; only the
    attachment is late-bound.

### Out (deferred, with homes)

- **In-store invoice email** — the counter customer gets the printed receipt; emailing an in-store
  invoice (when the walk-in gave an email) is a thin follow-up on the same INVOICE_ISSUED producer.
- **Credit-note / receipt email attachments** — same pipeline, later events (`CREDIT_NOTE_ISSUED`,
  `REFUND_EXECUTED`). Not this slice.
- **Discount lines** — `discount_total` is 0 in v1 (invoices don't proration-split); the template
  renders the row only when non-zero, so it's forward-ready.
- **ETA e-invoicing / signed / QR-fiscal receipts** — Egyptian Tax Authority submission is a separate
  compliance track; schema fields stay reserved (`deliver_issue_invoice.md` §Out).
- **Stored/cached PDFs** — rendered on demand every time (cheap, always authoritative). A render cache
  or object-storage archive is a later perf/retention concern, not correctness.
- **Localized document language** — v1 renders one language (matches `ENABLED_LOCALES=['en']`); the
  Arabic font is bundled so bidi customer data still shapes correctly.

---

## API contract

```
GET /api/orgs/{orgId}/invoices/{id}/pdf            → 200 application/pdf   (VIEWER)
GET /api/orgs/{orgId}/credit-notes/{id}/pdf        → 200 application/pdf   (VIEWER)
GET /api/orgs/{orgId}/sales-orders/{id}/receipt.pdf→ 200 application/pdf   (VIEWER)

# Deferred with Part C:
# GET /api/public/invoices/{token}[/pdf]           → anonymous customer access (not built)
```

Response headers on the PDF routes: `Content-Type: application/pdf`, `Content-Length: <n>`,
`Content-Disposition: inline; filename="INV-2026-0007.pdf"`.

### Errors

| Status | Cause |
|---|---|
| `400` | malformed `{id}` |
| `403` | caller has no role in `:orgId` (authed routes) |
| `404` | invoice / credit-note / order not found in the org; **or** (receipt) the order has no issued invoice yet; **or** (public) unknown/expired/wrong-purpose token — opaque, never distinguished |
| `405` | any non-GET on a document route |
| `409` / `422` | never — a document is a pure read of a frozen aggregate |

---

## Semantics

- **Pure read, no lock, org-scoped by invisibility** — same posture as the JSON detail reads the
  renderer sits on. A foreign invoice 404s.
- **Deterministic bytes** for a given aggregate + org profile + template version. No timestamps baked
  into the PDF beyond the aggregate's own frozen `issued_at`, so re-download reproduces the document.
- **Receipt eligibility:** `GET /sales-orders/{id}/receipt.pdf` requires an order with an issued
  invoice (any channel); it's framed for `IN_STORE` (tender/change present) but renders an online
  order's receipt too (tender/change blocks simply reflect that order's payments).
- **Money never recomputed:** every currency figure is the frozen decimal from the aggregate,
  formatted for display. The renderer has no arithmetic.

---

## Authorization

- Authed document routes: `AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER)` — a document is a
  read; system ADMIN bypasses org checks, as everywhere.
- Public routes: **anonymous by design** — the unguessable `VIEW_INVOICE` token, scoped to exactly one
  invoice (`resource_id = invoiceId`), is the capability (identical model to `VIEW_ORDER`, whose
  rationale is in `deliver_customer_email_notifications.md` §Authorization). A leaked link exposes one
  invoice, nothing else.
- Org billing-profile writes (Part A): OWNER via `PUT /api/orgs/{orgId}` (existing bar for org edits),
  ADMIN via `PATCH /api/admin/orgs/{orgId}`.

---

## File layout

**As built (Parts A + B):**

| Module | New / changed |
|---|---|
| `repository/.../db/migration` | New: `V51__Add_org_billing_profile.sql`. Regen jOOQ. |
| `domain` | Changed: `Org` (+profile fields as setters; 8-arg constructor unchanged). |
| `repository` | Changed: `OrgRepositoryImpl` (`update` writes + `toOrg` reads the profile columns). |
| `service` | New: `DocumentRenderService` (OpenPDF; invoice / credit-note / receipt). Changed: `OrgService` (5-arg `update` with a `BillingProfile` + `validateBillingProfile`). |
| `api` | New: PDF sub-routes — `GET /invoices/{id}/pdf`, `GET /credit-notes/{id}/pdf`, `GET /sales-orders/{id}/receipt.pdf` (each with a `writePdf` helper). Changed: `OrgResponse` / `UpdateOrgRequest` (+profile fields), `OrgHandler` (PUT wires profile), `AppConfig` (construct `documentRenderService`), `OrgServlet` (feed it to the three handlers). |
| root / `service` `pom.xml` | Add `com.github.librepdf:openpdf` (version property + `dependencyManagement` + module dep). |

**Deferred with Part C:** `V52` email-attachment migration; `NotificationType.INVOICE_ISSUED`;
`EmailMessage`/`SmtpEmailSender` multipart; `MagicLinkService` invoice link; `PublicInvoiceServlet`;
logo presign. None are built in this slice.

---

## Tests

**Delivered (green):**
- **`DocumentRenderServiceTest`** (service, `service/src/test/.../document/`) — feeds hand-built /
  mocked aggregates through the real renderer: a valid invoice renders non-empty `%PDF` bytes with
  filename = the invoice number; an org with **no** billing profile still renders (header =
  `org.name`); a credit note renders (filename = CN number, borrows the invoice's customer snapshot);
  the 80mm receipt renders with tender + change. 4 tests.
- **`OrgBillingProfileValidationTest`** (service) — the profile validation: null/all-null pass, a
  valid profile passes, over-length `legal_name` → 400, malformed `contact_email` → 400, blank email
  passes. 6 tests.
- **Regression:** the touched handler auth tests (`InvoiceHandlerAuthTest`, `MoneyReadsHandlerAuthTest`,
  the five `SalesOrderHandler` auth tests, `OrgAdminHandlerAuthTest`) + the full service (35) and api
  (159) unit suites stay green after the `Org` / `OrgService.update` / handler-constructor changes.

**Remaining tier (not in this slice):**
- **HTTP ITs** (`DocumentPdfHandlerIT`, `OrgBillingProfileIT`) that boot the servlet and assert
  `Content-Type: application/pdf` / `Content-Disposition` / 403 / 404 / 405 end-to-end — the render
  path and routing are unit- and compile-verified; the HTTP layer is driven by the frontend #23 e2e.
- **`InvoiceIssuedEmailIT`** — deferred with Part C.

**Build note:** the service module gained a **test-scope** `mockito-core` + a Java 25-compatible
`byte-buddy` 1.17.8 override (Mockito's bundled 1.14.x can't instrument on JDK 25 in isolation);
main packaging is unaffected.

---

## Acceptance criteria

**Rendering (Part B)**
1. `GET /invoices/{id}/pdf`, `/credit-notes/{id}/pdf`, `/sales-orders/{id}/receipt.pdf` each return a
   valid `application/pdf` (VIEWER-gated, org-scoped) whose text content reproduces the frozen numbers
   of the underlying aggregate — the renderer computes nothing.
2. The invoice/credit-note PDFs are A4; the receipt is 80mm and carries tender + change.
3. The document header shows the org billing profile when set, and degrades gracefully (falls back to
   `org.name`, omits missing rows) when not.
4. A VOID invoice / credit note renders unmistakably voided.

**Billing profile (Part A)**
5. An OWNER can set legal name / tax id / address / phone / contact email (and optional logo) on the
   org; a member below OWNER cannot; platform ADMIN can via the admin plane.

**Invoice-by-email (Part C — DEFERRED, not asserted in this slice)**
6. *(Deferred)* Issuing an invoice for an online order with a customer email would produce one
   `INVOICE_ISSUED` email with the PDF attached + a `VIEW_INVOICE` link. **ORDER_PAID stays
   unchanged and never attaches an invoice** (none exists at that point) — this holds today.

**Cross-cutting**
8. No new arithmetic anywhere in the render path; one renderer serves download, reprint, and email.
9. Existing JSON reads, delivery ITs, and the email pipeline stay green.

---

## What this unblocks

| Next | Depends on this |
|---|---|
| Frontend download/print + receipt print + branding editor | `frontst/stories/23_st_document_pdf.md` — consumes the three PDF routes + the profile fields |
| Credit-note / refund customer emails | reuse the attachment pipeline + a new event |
| ETA e-invoicing / fiscal receipts | the render service is the natural seam for a signed/QR variant |

---

## Addendum — letterhead logo (2026-07-13)

Part A stored `logo_object_key` and the plan above assumed the (deferred) HTML engine would inline
it; the shipped OpenPDF v1 renderer never used it — the letterhead was text-only. Now wired, still
on OpenPDF:

- `DocumentRenderService` takes a `LogoSource` (`objectKey → byte[]`) — production impl
  `PresignedLogoSource` presigns a GET (offline HMAC, `ObjectStorage` stays store-blind) and
  fetches over HTTP with tight timeouts (2s connect / 4s request). The old 4-arg constructor
  keeps a null source (text-only) for callers/tests without storage.
- **A4 (invoice + credit note):** the logo renders above the seller name, `scaleToFit(140×48pt)`.
  **Receipt (80mm):** centered above the header, `scaleToFit(100×40pt)`, slip height grows 48pt.
- **Degrade guarantee:** null key, fetch error, timeout, or undecodable bytes → the text-only
  header, logged at WARN — a broken logo must never break a finance document download.
- Tests: logo render is strictly larger than text-only (image stream present); a throwing source
  still renders the named PDF; receipt-with-logo renders.
