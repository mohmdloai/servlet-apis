# Slice: Place an online order

> Online flow **TX-1** per [`sys-analysis/outbound/FLOW.md`](../sys-analysis/outbound/FLOW.md) §4.
> Establishes the order anchor that every later outbound slice (reservation, payment, fulfillment, invoice) hangs off.

---

## Goal

`POST /api/orgs/{orgId}/sales-orders` creates a `sales_order` + `sales_order_line`s in **DRAFT**, then transitions atomically to **PENDING_PAYMENT** inside one DB transaction. Returns the full order with lines, frozen totals, and `expires_at`.

No reservations, no payment, no fulfillment in this slice.

---

## Scope

### In
- Endpoint: `POST /api/orgs/{orgId}/sales-orders`
- Customer upsert from `{name, email, phone, address}` on the request body
- `order_number` generation via a per-org per-year counter (new table)
- Line + order total computation (subtotal, tax, grand_total) — frozen on transition to PENDING_PAYMENT
- Idempotency via `(org_id, idempotency_key)` (V17's `UNIQUE` already enforces it)
- Channel hardcoded to `ONLINE` on this endpoint
- New domain `SalesOrderLine` (existing `SalesOrder` reused)
- New jOOQ repositories: `SalesOrderRepository`, `SalesOrderLineRepository`, `OrderNumberCounterRepository`
- New service: `SalesOrderService.placeOnlineOrder(...)`
- New handler: `SalesOrderHandler` registered under `"sales-orders"` in `OrgServlet.init()`
- New mapper: `SalesOrderMapper` (request DTO → service inputs, domain → response DTO) — lives in `api` module, called from the handler
- New migration **V27**: `order_number_counter` table

### Out (explicitly deferred to later slices)
- No `inventory_reservation` rows → **storefront can oversell** until the reservation slice lands. Acknowledged temporary gap.
- No availability check against current stock
- No `payment` / `payment_transaction` / `fulfillment`
- No TTL expiry worker (the `expires_at` field is set; no consumer yet)
- No PHONE / IN_STORE flow (separate endpoints/services later)
- No saved-cart workflow (DRAFT-staying orders) — DRAFT remains in the schema/state-machine for future slices (saved cart, PHONE pending review, admin-created pending approval)

---

## DRAFT vs PENDING_PAYMENT decision

DRAFT stays in the enum and state machine; it has real future uses (saved cart over multiple sessions, PHONE channel staff review, admin-created orders awaiting approval). **This** endpoint inserts at DRAFT and updates to PENDING_PAYMENT inside the same transaction — DRAFT is therefore observable only intra-txn for the "place online order" path, but the column-level state machine remains correct for the future flows.

---

## API contract

### Request
```
POST /api/orgs/{orgId}/sales-orders
Idempotency-Key: <opaque>     # REQUIRED for ONLINE channel; per-org unique
Content-Type: application/json
```

The `Idempotency-Key` header is **mandatory** for the storefront-facing ONLINE endpoint
(see `sys-analysis/outbound/FLOW.md` §6 and the `SalesOrder.createDraft` invariant).
Missing or blank → `400`.
```json
{
  "customer": {
    "name":    "Mona Hassan",
    "email":   "mona@example.com",
    "phone":   "+20 100 000 0000",
    "address": "12 Tahrir St, Cairo"
  },
  "lines": [
    { "product_id": "<uuid>", "quantity": 3 },
    { "product_id": "<uuid>", "quantity": 1 }
  ],
  "notes": "optional free text"
}
```

`channel` is not accepted from the client on this endpoint — it is forced to `ONLINE`.

### Response — `201 Created`
Full order with lines:
```json
{
  "id": "<uuid>",
  "org_id": "<uuid>",
  "customer_id": "<uuid>",
  "order_number": "SO-2026-00042",
  "channel": "ONLINE",
  "status": "PENDING_PAYMENT",
  "subtotal":       "...",
  "tax_total":      "...",
  "discount_total": "0.00",
  "grand_total":    "...",
  "currency": "EGP",
  "prepaid_amount": "0.00",
  "placed_at":  "<ISO-8601>",
  "expires_at": "<placed_at + 24h>",
  "notes": "...",
  "lines": [
    {
      "id": "<uuid>",
      "product_id":     "<uuid>",
      "description":    "<product name snapshot>",
      "quantity":       3,
      "unit_price":     "...",
      "tax_rate":       "0.0000",
      "line_subtotal":  "...",
      "line_tax":       "...",
      "line_total":     "..."
    }
  ]
}
```

Rationale for the full DTO over `{id, order_number, status}`: the client needs `expires_at` (payment-countdown UI), `grand_total` (the amount to charge), and the line snapshot (order-summary screen) immediately after placement. Returning a stub forces a second GET for data already in memory from the INSERT.

### Errors
| Status | Cause |
|---|---|
| `400` | empty `lines`, any `quantity <= 0`, malformed body, customer fields missing, **missing `Idempotency-Key` header** |
| `403` | caller has no role in `:orgId` |
| `404` | a `product_id` does not exist or does not belong to `:orgId` |
| `200`* | duplicate `Idempotency-Key` returns the previously-created order verbatim (retry-safe, not an error) |

*Implementation: detect "already exists" before the INSERT and return the existing row.

---

## DB changes — `V27__Create_order_number_counter.sql`

```sql
CREATE TABLE order_number_counter (
    org_id    UUID   NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    year      INT    NOT NULL,
    next_val  BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (org_id, year)
);
```

Claim a number atomically inside the order-creation txn:
```sql
INSERT INTO order_number_counter (org_id, year, next_val)
VALUES (:orgId, :year, 2)
ON CONFLICT (org_id, year) DO UPDATE
    SET next_val = order_number_counter.next_val + 1
RETURNING next_val - 1;
```
Java formatting:
```java
String orderNumber = String.format("SO-%d-%05d", year, claimedVal);
// e.g. claimedVal=42, year=2026 → "SO-2026-00042"
```

`order_number` is **not** required to be gapless. Only `invoice_number` and `credit_note_number` have that tax-compliance constraint. Gaps from aborted txns are acceptable here.

---

## Service contract

```java
public SalesOrder placeOnlineOrder(
    UUID orgId,
    CustomerInput customer,           // name, email, phone, address
    List<OrderLineInput> lines,       // productId, quantity
    String idempotencyKey,            // nullable
    String notes                      // nullable
);
```

All steps in **one** `dsl.transactionResult(...)`:

1. **Idempotency short-circuit**: if `idempotencyKey != null`, SELECT existing order by `(org_id, idempotency_key)`; if found, load lines and return mapped result without further work.
2. **Customer upsert** on `(org_id, email)` (V15's `customer_org_email_unique`):
   ```sql
   INSERT INTO customer (id, org_id, name, email, phone, address)
   VALUES (...)
   ON CONFLICT (org_id, email) DO UPDATE
       SET name = EXCLUDED.name, phone = EXCLUDED.phone, address = EXCLUDED.address
   RETURNING id;
   ```
3. **Product snapshot per line**: SELECT `product.name, base_price, tax_rate?` for each `product_id` filtered by `org_id = :orgId`. Reject (404) if any missing. Snapshot:
   - `description = product.name`
   - `unit_price  = product.base_price`
   - `tax_rate   = 0` (v1 default until a tax-rate column is added; story acknowledges this temporary)
4. **Line totals**:
   - `line_subtotal = quantity * unit_price`
   - `line_tax      = line_subtotal * tax_rate`
   - `line_total    = line_subtotal + line_tax`
5. **Order totals**:
   - `subtotal      = SUM(line_subtotal)`
   - `tax_total     = SUM(line_tax)`
   - `discount_total = 0`
   - `grand_total   = subtotal + tax_total - discount_total`
6. **Claim order_number** via counter table (statement above). Compute `SO-YYYY-NNNNN`.
7. **INSERT sales_order**: start at `status = 'DRAFT'`, channel `'ONLINE'`, totals set, `placed_at = now()`, `expires_at = now() + INTERVAL '24 hours'`, `idempotency_key` set.
8. **INSERT sales_order_line** rows in a batch.
9. **UPDATE sales_order SET status = 'PENDING_PAYMENT'** for this order (same txn).
10. Return assembled `SalesOrder` with lines for the mapper.

Validation throws `ValidationException` (400) and `NotFoundException` (404) from `common`. Order-state-transition errors throw `InvalidOrderTransitionException` (already drafted in `common`).

---

## File layout

| Module | New / changed |
|---|---|
| `domain` | New: `SalesOrderLine.java`. Existing: `SalesOrder.java` (already untracked in git, finalize). |
| `repository/src/main/resources/db/migration` | New: `V27__Create_order_number_counter.sql`. |
| `repository` | New: `SalesOrderRepository` + `Impl`, `SalesOrderLineRepository` + `Impl`, `OrderNumberCounterRepository` + `Impl`. Regenerate jOOQ sources after V27 lands (`mvn generate-sources -Pcodegen -pl repository`). |
| `service` | New: `SalesOrderService` with `placeOnlineOrder(...)` and DTO inputs (`CustomerInput`, `OrderLineInput`). |
| `api` | New: `SalesOrderHandler implements OrgResourceHandler`; `SalesOrderMapper` (request → service input, domain → response). Register `"sales-orders"` in `OrgServlet.init()`. Wire dependencies in `AppConfig`. |

---

## Authorization

- Caller must have any org role on `:orgId` — reuse `AuthzHelper.requireOrgAccess(orgId, securityContext)`.
- v1 has no customer-facing auth; the storefront acts as a proxy with an org-scoped service role until [`customer-portal-future.md`](../docs/customer-portal-future.md) lands. The story does **not** open this endpoint to anonymous callers.

---

## Acceptance criteria

- [ ] `POST /api/orgs/{orgId}/sales-orders` with a valid body returns `201` + full order DTO + lines, `status = "PENDING_PAYMENT"`, `order_number` matching `^SO-\d{4}-\d{5}$`, `placed_at` and `expires_at` set with `expires_at = placed_at + 24h`.
- [ ] Two concurrent posts with the same `Idempotency-Key` produce exactly one `sales_order` row; both responses carry the same `id` and `order_number`.
- [ ] `grand_total` equals `SUM(line_total)` within `NUMERIC(14,2)` precision; per-line math checks out.
- [ ] Empty `lines` array → 400. `quantity <= 0` on any line → 400.
- [ ] Any `product_id` not belonging to `:orgId` → 404.
- [ ] An unseen `customer.email` for the org creates a new `customer` row; a known email reuses it. `customer_id` on the order points at the upserted row.
- [ ] After successful order placement, the following row counts are unchanged: `inventory_reservation`, `payment`, `payment_transaction`, `fulfillment`. (Asserted in integration test.)
- [ ] Two orders in the same year and org get consecutive numbers (e.g. `SO-2026-00001`, `SO-2026-00002`); concurrent inserts don't duplicate numbers (`UNIQUE (org_id, order_number)` plus the atomic counter guarantees this).
- [ ] Caller without a role in `:orgId` → 403.

---

## What this unblocks

| Next slice | Depends on this |
|---|---|
| **Reserve stock at PENDING_PAYMENT** | needs the order, its lines, and `expires_at` to mirror onto reservations |
| **Record InstaPay txn + reconcile to order** | reconciliation key is `order_number` from this slice |
| **TTL expiry worker** | scans `idx_so_pending` (already in V17) for `expires_at < now()` |
| **Fulfillment + invoicing chain** | requires `sales_order` + lines to exist before any fulfillment can target them |
| **In-store checkout (single-txn)** | independent endpoint, but shares `SalesOrder`/`SalesOrderLine` domain + the counter table |
