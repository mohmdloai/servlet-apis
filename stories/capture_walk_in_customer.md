# Slice: Capture walk-in customer contact on an in-store sale

> Follow-up to [`in_store_sale.md`](./in_store_sale.md). The in-store checkout already accepts an
> optional `customer` block, but today it is **only** honoured when an `email` is present — a
> cashier who types a **name and/or mobile with no email** has those details silently discarded
> (`SalesOrderService.resolveCustomer` returns `null` when `hasEmail` is false). This slice captures
> the name/phone the cashier actually entered, without requiring anything, and echoes it back so the
> UI can display what it sent.
>
> **Revised 2026-08-26 against `master` @ V86.** The original draft predated three things it now
> has to sit beside: the `Text` normalizer (V62, replaced the per-service `trimOrNull`), the
> per-order **delivery** contact on `sales_order` (V80), and the customer `phone_e164` twin (V79).
> None of them changes the decision; V80 settles the invoice question that the draft left open.

---

## Goal

For an `IN_STORE` sale, capture the walk-in customer's **name** and **phone** when provided — even
with no email — as an optional snapshot on the order, and return them in the response.

Done means:
- Cashier enters name + mobile, no email → the sale succeeds, the response carries
  `customer_name` + `customer_phone` (previously both were dropped), and the invoice / 80 mm receipt
  prints that name instead of "Walk-in customer".
- Cashier enters **nothing** → unchanged: fully anonymous sale, no CRM record, no snapshot.
- Cashier enters an **email** (± name/phone) → unchanged: existing CRM upsert on `(org_id, email)`.

Nothing new is ever *required*. The only behavioural change is that provided details stop being
thrown away.

---

## Why approach C (order-level snapshot in its own columns)

A walk-in customer may prefer to give **no** personal details at all, so identity must stay fully
optional — the fix cannot introduce any required field or new mandatory identity. Three shapes were
considered:

- **CRM record keyed on phone** — relax `customer.email NOT NULL`, add `UNIQUE (org_id, phone)`,
  upsert on phone when email absent. Gives returning-customer recognition, but drags the
  customer-identity model (email-keyed, and since V79 the identity a notification channel dials)
  into every anonymous counter sale and changes an invariant the online path relies on.
- **Reuse V80's `delivery_recipient` / `delivery_phone`** — zero migration, and `InvoiceService`
  would print the name for free because it already prefers `delivery_recipient`. Rejected: those
  columns carry a documented contract — *"who receives this parcel"*, *"the number a courier calls
  for THIS order"*, *"NULL for IN_STORE"* — and the self-delivery epic (rider custody, frontst story
  47) will read `delivery_phone` as exactly that. A counter sale has no parcel and nobody to dial; a
  walk-in's mobile must never become a courier's call target. One column cannot mean both.
- **Order-level snapshot in its own columns (chosen)** — store the entered `customer_name` /
  `customer_phone` directly on `sales_order`, next to `customer_id`, mirroring the frozen
  `customer_name` / `customer_phone` block `sales_invoice` has carried since V21. No CRM entity for
  walk-ins, `customer` stays email-keyed and untouched, `delivery_*` stays a delivery contact, and a
  sale with no details written stores nothing. The detail is a receipt/contact convenience for that
  one sale, which is what the counter flow needs today.

Graduating a snapshot into a real CRM customer (loyalty, purchase history, dedup on phone) is a
later slice if the need appears — this slice deliberately does not build it.

---

## Data model

New migration **`V87__Sales_order_walkin_contact.sql`** (V86 is HEAD; nothing local claims V87):

```sql
ALTER TABLE sales_order
  ADD COLUMN customer_name  TEXT,
  ADD COLUMN customer_phone TEXT;

COMMENT ON COLUMN sales_order.customer_name IS
  'Walk-in buyer''s name as typed at the counter, frozen at sale. Only ever set when customer_id IS NULL (an email sale is owned by the CRM row). Not a delivery contact (see delivery_recipient, V80).';
COMMENT ON COLUMN sales_order.customer_phone IS
  'Walk-in buyer''s phone as typed at the counter (Text.normalizeNumeric — Arabic-Indic digits folded, not E.164). Nothing dials it; no phone_e164 twin. Only ever set when customer_id IS NULL.';
```

- Both **nullable**, no default, no constraint — a snapshot, not an identity. No unique index (a
  repeat walk-in is a new sale, not a recognised customer — that is the explicit scope boundary). No
  search index: nothing queries orders by walk-in name, and the walk-in never appears in customer
  search (no CRM row) — same "no index, no backfill" reasoning as V80.
- Existing rows backfill to `NULL` (anonymous), matching today's behaviour.
- These columns are **independent of** `customer_id`: an email sale sets `customer_id` and leaves
  the snapshot columns null (the CRM record is the source of truth); a phone/name-only walk-in sets
  the snapshot columns and leaves `customer_id` null; an anonymous sale leaves all three null.
- jOOQ regen is automatic — the `repository` build generates from the migrated live schema.

---

## Behaviour matrix (in-store)

| Cashier enters | `customer_id` | `customer_name` / `customer_phone` | Invoice `customer_name` | Change |
|---|---|---|---|---|
| nothing | NULL | NULL / NULL | "Walk-in customer" | **unchanged** — anonymous |
| email (± name/phone) | resolved (CRM upsert) | NULL / NULL | CRM name → email | **unchanged** — CRM owns it |
| name and/or phone, **no email** | NULL | normalised values (or NULL if blank) | the snapshot name (phone → invoice `customer_phone`) | **new** — previously dropped |

Only the third row changes. Normalisation reuses exactly what the CRM path already applies in
`resolveCustomer`: `Text.normalizeText(name)` and `Text.normalizeNumeric(phone)` — blank collapses
to `NULL`, Arabic-Indic digits fold to ASCII, `'+'` is kept. No E.164 derivation and no format
check: there is no phone-keyed identity to dedup against and nothing that dials the number.

---

## Scope

### In
- Migration `V87` adding `customer_name`, `customer_phone` to `sales_order`.
- `SalesOrder` domain model gains the two optional fields + a `setWalkInContact(name, phone)` that
  mirrors `setDeliveryContact` (V80). `buildDraftOrder` is **not** touched: in `createInStoreSale`,
  after `BuiltOrder built = buildDraftOrder(...)` and before `repo.insert(...)`, set the snapshot
  **only when `built.customer() == null`** (email absent) and the input carried a name and/or phone.
  When `resolveCustomer` returned a `Customer` (email path), the snapshot columns stay null.
- `SalesOrderRepositoryImpl`: `insert(...)` writes both columns beside the `DELIVERY_*` sets; the
  row → domain mapper carries them back beside `setDeliveryContact`.
- `SalesOrderResponse` gains `customer_name` / `customer_phone` (Jackson drops nulls, so anonymous
  and email sales serialise unchanged) — this flows into the embedded `order` of
  `InStoreSaleResponse` automatically. **Staff plane only:** `forCustomerView` withholds them, the
  same rule as `notes` and `delivery_*` — one rule for every internal contact field on the
  anonymous magic-link view. (A walk-in can never reach that view anyway — no email, no link — so
  this costs nothing and keeps the rule uniform.)
- **Invoice snapshot — resolved, in scope.** `sales_invoice.customer_name` (NOT NULL) and
  `customer_phone` exist since V21; `InvoiceService.invoiceCustomerName` currently returns the
  hard-coded `"Walk-in customer"` for a null customer, and the PDF renderer prints that string.
  Extend the existing fallback chain by one link, after `delivery_recipient` and before the
  walk-in literal: `delivery_recipient → order.customer_name → CRM name → CRM email →
  "Walk-in customer"`. Phone: `firstNonBlank(delivery_phone, order.customer_phone, CRM phone)`.
  Because a walk-in has neither a delivery contact nor a CRM row, the invoice's contact block *is*
  the snapshot — and the 80 mm receipt PDF prints the name with **no renderer change**.

### Out (deferred)
- **No CRM record for walk-ins** — no `customer` row, no `customer_id`, no phone-keyed identity.
- **No returning-customer recognition / dedup** — every walk-in is a fresh snapshot.
- **No E.164 twin / no validation** — `customer.phone_e164` (V79) exists because notification
  channels dial it; `NotificationService` resolves recipients through `customer_id`, so a walk-in
  is unreachable by construction. A WhatsApp/SMS receipt to a walk-in is a later slice; it adds the
  twin (via `Phone.toE164`) when something consumes it.
- **`delivery_*` untouched** — never written for `IN_STORE`, exactly as V80 documents.
- **Online / storefront / portal paths untouched** — `ONLINE`/`PHONE` still require
  `customer.email`; snapshot columns are never written for those channels.
- **No new required fields anywhere** — the customer block stays entirely optional for in-store.
- **No order search by walk-in name** — the orders worklist does not surface a customer today; a
  later story may show the snapshot on the IN_STORE order card/detail.

---

## API contract

### Request — `POST /api/orgs/{orgId}/sales-orders` (unchanged shape)
```json
{
  "channel": "IN_STORE",
  "customer": { "name": "Mohamed gamal", "phone": "01006123584" },
  "lines": [ { "product_id": "<uuid>", "quantity": 1 } ],
  "payment": { "provider": "CASH" }
}
```
- `customer` remains **optional**; within it, **every** field is optional for `IN_STORE`. `name` and
  `phone` with no `email` are now captured (previously silently dropped).
- No request-schema change — `PlaceSalesOrderRequest.CustomerPayload` already carries `name`/`email`/`phone`/`address`, and
  the admin `/sell` screen already sends exactly this block (frontst `placeInStoreSale.request.ts`).

### Response — `201 Created` (order block gains two fields)
```json
{
  "order": {
    "id", "order_number", "channel": "IN_STORE", "status": "CLOSED",
    "customer_id": null,
    "customer_name": "Mohamed gamal",
    "customer_phone": "01006123584",
    "grand_total", "prepaid_amount", "lines": [ ... ]
  },
  "invoice": { "customer_name": "Mohamed gamal", "customer_phone": "01006123584", ... },
  "payment": { ... }, "fulfillment": { ... }
}
```
- `customer_name` / `customer_phone` present only when captured; omitted (null-dropped) for
  anonymous and email-resolved sales. Withheld on the customer view (`forCustomerView`).

### Errors
No new error cases — capturing optional details cannot fail validation. Existing in-store errors
(`400` bad lines/provider/tender, `403` non-STAFF, `409` insufficient stock) are unchanged.

---

## Authorization
Unchanged — `AuthzHelper.requireOrgAccess(orgId, STAFF)`, system ADMIN bypasses (the in-store POST
gate from `in_store_sale.md`).

---

## Tests

Extend `api/src/test/java/.../sale/InStoreSaleIT.java` (TestContainers, drives the service):
- **name + phone, no email** *(the bug repro)*: sale succeeds; `sales_order.customer_id` NULL,
  `customer_name`/`customer_phone` persisted; response order block echoes both; the minted
  invoice's `customer_name` is the typed name and its `customer_phone` the typed phone (extend the
  existing `invoiceCustomerName(...)` helper with a phone twin).
- **phone only, no name**: `customer_phone` set, `customer_name` NULL; invoice name still
  "Walk-in customer", invoice phone set.
- **Arabic-Indic digits** (`٠١٠٠٦١٢٣٥٨٤`): stored as `01006123584` — the `Text.normalizeNumeric`
  contract, so a cashier on an Arabic keyboard gets the same row as one on an English keyboard.
- **blank/whitespace name & phone**: both normalise to NULL (treated as anonymous).
- **anonymous (no customer block)**: `customer_id`, `customer_name`, `customer_phone` all NULL —
  regression, behaviour identical to today (`walkIn_noCustomer_nullCustomerId_walkInInvoiceSnapshot`
  gains the two NULL assertions).
- **email present (± name/phone)**: existing CRM upsert path — `customer_id` set, snapshot columns
  NULL (email is the source of truth); invoice name comes from the CRM row. Regression against the
  current `cashSale_...` (Nadia) case — add the two NULL assertions there.
- Regression: existing `InStoreSaleIT` happy-path / walk-in / online-routing cases still pass; the
  online path never writes the snapshot columns.

`api/src/test/java/.../dto/SalesOrderResponseCustomerViewTest.java`:
- `customerView_omitsWalkInContact` — a staff view carries `customer_name`/`customer_phone`; the
  customer view returns null for both (same shape as the existing `customerView_omitsNotes`).

---

## Frontend companion (frontst — separate, numbered story)

The `/sell` screen already collects name + phone (no email input) and posts the block, so the
backend slice alone makes the typed contact persist and print on the receipt PDF. What the admin
still needs, in a story of its own (next number in `frontst/stories/`):
- `packages/entities/salesOrder`: `customer_name` / `customer_phone` on `SalesOrderDTO` + mapper
  (`customerName` / `customerPhone`).
- `apps/admin/e2e/mock-api.mjs` in-store handler: echo the two fields, and **fix the mock's fake
  identity** — it mints a `customer_id` whenever a name is given, which contradicts the backend
  (no email ⇒ `customer_id` NULL).
- `features/inStoreSale/ui/Receipt.tsx`: a "Sold to" row (name · phone) under the order/invoice
  rows when the response carries a snapshot.
- `e2e/mobile-in-store-sale.spec.ts`: open the collapsed customer affordance, type name + phone,
  charge, assert the receipt row — today there is zero customer coverage.

---

## Branch
`163_feat/walk-in-contact` off `master` (162 = collection image, merged). Docs-only until then.
