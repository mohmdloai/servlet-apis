# Slice: Refund from the receipt — a counter return in one transaction

> Loyverse gap #3, after [`capture_walk_in_customer.md`](./capture_walk_in_customer.md) (V87) and
> [`counter_discount.md`](./counter_discount.md) (V88). Composes — does not replace — the machinery
> of [`credit_note_refund.md`](./credit_note_refund.md) and the documented happy path in
> `sys-analysis/outbound/refund.md` §"customer returns goods". **Branch `165_feat/counter-return`
> off `master`, migration V89** — verify the highwater at branch time.

---

## Goal

A customer comes back to the counter with a receipt. The cashier's manager finds the sale, picks
which items (and how many) come back, and taps once — the books get a `RETURN` credit note against
that sale's invoice, the cash goes back across the counter as an **already-executed** refund, and
the goods go **back on the shelf** — all in one transaction, all or nothing.

Done means:
- In-store cash sale of 3 × 100 with a 10% counter discount (grand 270). Customer returns 1. One
  POST → credit note `CN-…` for **90.00** (100 gross, 10 discount share, 0 tax), cash refund
  **EXECUTED** for 90.00, payment `PARTIALLY_REFUNDED`, stock +1 with reason `RETURNED` linked to
  the order, invoice **still PAID**. Returning the other two later → 180.00, and the two notes sum
  to exactly 270.00.
- The same on an InstaPay sale creates the refund **PENDING** (the merchant sends the transfer,
  then executes it from the refunds queue — the existing two-step) while the credit note and the
  restock happen now.
- The cashier finds the sale by scanning the **barcode the receipt now prints**, typing the order
  number, or picking from today's counter sales.

---

## What exists, what is missing

`credit_note_refund.md` already built every money primitive and `refund.md` already documents this
exact happy path — in **three admin screens and three transactions**: issue the credit note (typing
the lines and prices), create the refund, execute the refund. For a delivered online order that
is right: the goods are elsewhere, the money goes back by transfer, and a manager works it from a
desk. At a counter it is nine taps with the customer watching, and it has three gaps the primitives
never had to face:

1. **Nothing restocks.** A credit note has never touched inventory (`CreditNoteService` imports
   nothing from it); `StockReason.RETURNED` ("Customer Return") has been in the DB enum since V5
   and **no code path has ever written it**. Today a return's stock is fixed by a manual
   `adjust`, or not at all.
2. **Nothing prorates the counter discount.** A credit-note line carries the invoice line's gross
   `unit_price`; the cumulative cap is the invoice's **net** `grand_total`. On the 3 × 100 − 10%
   sale, returning one unit through the admin form credits 100 for goods the customer paid 90 for,
   and returning all three is refused ("300 exceeds 270") — there is no way to get it right.
3. **The money never moves in the same breath as the paperwork.** At the counter the cash is handed
   back the moment the note is issued — the situation `RefundService.createExecutedChangeInTx`
   already recognises for change ("there is no PENDING window").

Plus one usability gap that becomes a correctness gap under pressure: the receipt prints the order
number as text only, and the order worklist cannot filter by channel — so "find the sale" is
"type `SO-2026-00417` correctly while the customer waits".

---

## Why this shape

- **A composite over the primitives, not a fourth path.** `CounterReturnService.returnFromReceipt`
  runs `issue → create → execute → restock` inside **one** `transactionResult`, calling in-transaction
  variants of the existing services (`CreditNoteService.issueInTx`, the existing
  `Refund.createPending` + `executeCreditNoteBacked`) — the same ledger rows, the same DEBIT
  transaction, the same allocation unwind, the same `SETTLED` flip. Nothing about a counter return is
  a different *kind* of refund; it is the same refund with no waiting room.
- **The receipt is the authority for prices.** Lines are `{product_id, quantity}` only; description,
  `unit_price`, `tax_rate` are copied from the **invoice line**, never from the client. The admin
  `POST /credit-notes` keeps accepting typed lines (pricing errors need that); the counter path is
  the one that must not.
- **Cash executes now; a transfer stays PENDING.** The refund's method is the sale's tender: a
  `CASH` sale refunds `CASH`, executed in the same transaction (the counter-change precedent); an
  `INSTAPAY_IN_STORE` sale creates a `PENDING` refund with method `INSTAPAY_MANUAL` — the merchant
  cannot push a wallet transfer from here, so the two-step lifecycle stays exactly as documented.
  The restock does not wait on the money either way: the goods are physically back.
- **Discount proration is the invoice's own rule, one level down.** `InvoiceService.discountToBill`
  already splits an order's discount across invoices with the completing invoice taking the exact
  remainder. The same rule splits an invoice's `discount_total` across its returned lines: each
  line's share is `round(discount_total × line_gross / invoice.subtotal)` HALF_EVEN, except that the
  return which brings the invoice's **credited quantity to its billed quantity** takes the exact
  remaining discount. So partial returns are fair to the piastre and a complete return sums to
  exactly `grand_total`. Tax stays on the gross line, as the invoice charged it (the coupon/counter
  convention).
- **Restock is a choice, default yes.** `restock: false` for damaged goods the customer is still
  refunded for. It is per return, not per line, in v1.
- **Idempotent, because it moves money.** `Idempotency-Key` required (the in-store sale's rule); a
  replay returns the prior result with `200`, never a second note.
- **The gate is what it already is.** MANAGER (a credit note and a refund are MANAGER actions
  today); **above the org threshold → OWNER**, enforced by the *existing* cumulative-credit gate
  inside issuance — not re-implemented, so a counter return and a desk-issued note against the
  same invoice share one bar. STAFF → plain 403, as for any credit note.

---

## Data model — **V89** `Credit_note_counter_return`

```sql
ALTER TABLE credit_note
    ADD COLUMN discount_total   NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (discount_total >= 0),
    ADD COLUMN restocked_at     TIMESTAMPTZ,
    ADD COLUMN idempotency_key  TEXT;
CREATE UNIQUE INDEX credit_note_idem_idx ON credit_note (org_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
```

- `discount_total` — the returned lines' share of the invoice's discount; `total = subtotal +
  tax_total − discount_total`. Existing notes backfill to 0 and their arithmetic is unchanged. The
  credit-note PDF gains a `Discount` row when > 0 (the invoice PDF already has one).
- `restocked_at` — set when the return put goods back (`fulfillment.returned_at`'s shape); NULL for
  `restock:false` and for every note issued from the admin form. The inventory ledger rows are the
  detail; this is the flag a credit-note screen can read without scanning the ledger.
- `idempotency_key` — nullable; only the counter path writes it.
- No `stock_reason` change: `RETURNED` exists (V5). No new table.

---

## Application & wire

### Find the sale
- **Receipt barcode.** `renderReceipt` prints a **Code128** of the order number (OpenPDF
  `Barcode128`, ~10 mm tall, under the order/date lines). The admin scanner's format set already
  includes `code_128`. No other document changes.
- `GET /api/orgs/{orgId}/sales-orders?channel=IN_STORE` — one more predicate on the worklist read,
  AND-composed with `?status=`; unknown value → 400 naming the three channels. Queue-vs-ledger
  ordering stays keyed on `status`. `?order_number=` is unchanged (exact lookup).

### Preview — `GET /api/orgs/{orgId}/sales-orders/{id}/returnable` (VIEWER)
```json
{
  "order": { "id", "order_number", "channel": "IN_STORE", "status": "CLOSED" },
  "sales_invoice_id": "<uuid>",
  "tender": "CASH",
  "refund_mode": "IMMEDIATE_CASH",
  "lines": [
    { "product_id": "<uuid>", "description": "Sugar 1kg", "unit_price": 100.00, "tax_rate": 0.00,
      "billed": 3, "returned": 1, "returnable": 2, "unit_refund": 90.00 }
  ]
}
```
- `refund_mode` ∈ `IMMEDIATE_CASH` (CASH tender) · `PENDING_TRANSFER` (InstaPay) — the client says
  the right sentence before the tap. `unit_refund` is the prorated net per unit **for display**; the
  POST recomputes with the remainder rule and is the authority.
- 404 unknown order; **409** when the order is not `IN_STORE` + `CLOSED` (the message names the
  admin credit-note route for everything else).

### Return — `POST /api/orgs/{orgId}/sales-orders/{id}/return` (MANAGER; `Idempotency-Key` required)
```json
{ "lines": [ { "product_id": "<uuid>", "quantity": 1 } ], "restock": true, "reason_note": "wrong size" }
```
Inside one transaction:
1. Lock the order (`FOR UPDATE`); require `IN_STORE` + `CLOSED` (409 otherwise); load its single
   live (non-VOID) invoice, `PAID`.
2. Validate lines: non-empty, product on the invoice (400), `quantity ≥ 1`, and `quantity ≤ billed −
   already credited` for that product across the invoice's non-VOID notes (**409** naming the
   returnable count — a new `CreditNoteRepository.creditedQuantityByProduct(invoiceId)`).
3. Build the note: lines copied from the invoice lines; `discount_total` by the proration rule;
   `reason = RETURN`; `idempotency_key`. `CreditNoteService.issueInTx(txDsl, …)` — the existing
   body of `issue` moved under a txn-taking method, with `issue` becoming a one-line wrapper. The
   cumulative cap and the **OWNER threshold gate** run unchanged inside it.
4. Refund: `Refund.createPending(credit_note_id, amount = note.total, method = tender == CASH ?
   CASH : INSTAPAY_MANUAL)`; when `CASH`, execute it in the same txn (`executeCreditNoteBacked` +
   the VERIFIED DEBIT `cash` transaction, `provider_ref` synthesised from the refund id as the
   change path does) → `EXECUTED`, allocations unwound FIFO, payment recomputed, note `SETTLED`.
5. Restock when `restock`: per line `inventoryRepo.adjustQuantities(+qty, 0)` +
   `inventory_log(RETURNED, order_id)` — the `FulfillmentService.returnGoods` shape with the reason
   this enum value was minted for; `credit_note.restocked_at = now`. A product with **no inventory
   row** → 409 naming it (retry with `restock:false`).
6. Replay on the same key → **200** with the same result; a different body on the same key → 409.

Response `201`:
```json
{
  "credit_note": { "id", "credit_note_number", "status": "SETTLED", "subtotal": 100.00, "tax_total": 0.00,
                   "discount_total": 10.00, "total": 90.00, "restocked_at": "…", "lines": [ … ] },
  "refund": { "id", "status": "EXECUTED", "amount": 90.00, "method": "CASH", "payment_transaction_id": "…" },
  "stock": [ { "product_id": "<uuid>", "quantity": 1, "stock_after": 8 } ]
}
```
For an InstaPay sale: `refund.status = PENDING`, `method = INSTAPAY_MANUAL`, note `ISSUED`.

### Errors
| Status | Cause |
|---|---|
| 400 | missing `Idempotency-Key`; empty lines; product not on the invoice; `quantity < 1`; `reason_note` > 500 |
| 403 | not MANAGER+; **`APPROVAL_REQUIRED` (`required_role: OWNER`)** when the invoice's cumulative credited total would exceed `refund_approval_threshold` (the existing gate, existing envelope) |
| 404 | unknown order |
| 409 | order not `IN_STORE`/`CLOSED`; quantity above returnable; untracked product with `restock:true`; key replayed with a different body |

Nothing is written before any of them.

---

## Out (deferred)
- **Returns on `ONLINE`/`PHONE` orders** — the admin credit-note flow, unchanged; a courier
  return is `rider_self_delivery.md`'s.
- **Exchanges** (return + new sale in one ticket) — two transactions today, one screen later.
- **Store credit** (refund.md's own exclusion), **per-line restock choice**, **partial-quantity
  damaged split**, **cash-drawer accounting** (Loyverse's shift report — a different epic).
- **Void-the-whole-sale shortcut** — it is a full return; no separate verb.

---

## Tests

`api/src/test/java/.../sale/CounterReturnIT.java` (the `InStoreSaleIT` harness; models
`CreditNoteRefundIT` for the ledger assertions):
- **Full cash return, discounted sale** (3 × 100, −10%): note `total 270.00` / `discount_total
  30.00` / `SETTLED`; refund `EXECUTED CASH 270.00` with a VERIFIED DEBIT `cash` transaction linked;
  payment `REFUNDED`, `refunded_amount 270.00`; **invoice stays PAID, `paid_amount` unchanged**;
  three `RETURNED` ledger rows carrying the order id, stock back to the pre-sale figure;
  `restocked_at` set; response `stock[].stock_after` matches the ledger.
- **Partial then completing**: 1 of 3 → `90.00` (share 10.00); then 2 of 3 → `180.00` (share =
  exact remainder 20.00); Σ notes = 270.00; payment `PARTIALLY_REFUNDED` then `REFUNDED`.
- **Proration rounding**: 3 × 33.33 at 7% off, returned one at a time — every share HALF_EVEN, the
  last one the remainder, Σ = grand exactly.
- **Tax**: org `tax_rate 0.14` → note `tax_total` = the line's invoice tax; total = gross + tax −
  share.
- **Over-quantity**: 4 of 3 → 409; 1 more after 3 returned → 409 naming `returnable 0`; nothing
  written.
- **InstaPay sale**: refund `PENDING INSTAPAY_MANUAL`, note `ISSUED`, restock done now; then
  `RefundService.execute` settles it exactly as a desk refund would.
- **`restock:false`**: no ledger rows, stock unchanged, `restocked_at` NULL, money identical.
- **Untracked product with `restock:true`** → 409, nothing written (money not moved).
- **Authz**: STAFF → 403 plain; MANAGER under threshold → ok; **threshold 100, return 270 by
  MANAGER → 403 `APPROVAL_REQUIRED` `OWNER`**, nothing written; OWNER → ok; ADMIN → ok.
- **Wrong order shape**: ONLINE order → 409; a CLOSED in-store order whose invoice is VOID (after a
  reissue) → resolves the live one.
- **Idempotency**: same key twice → one note, one refund, one set of ledger rows, second call 200;
  same key different body → 409; missing header → 400.
- **Regression**: `CreditNoteRefundIT`, `InStoreSaleIT`, `CounterDiscountIT` untouched and green;
  the admin `POST /credit-notes` still issues with `discount_total 0`.
- `?channel=`: a small `SalesOrderReadsIT` case — filter + unknown value 400 (the `?status=`
  pattern).
- `DocumentRenderServiceTest`: `renderReceipt_carriesOrderNumberBarcode` (the receipt still
  renders, the page budget grows by the barcode height, and the content stream contains the
  Code128 bars — pin the least brittle of those at implementation); `renderCreditNote_printsDiscountRowWhenPresent`.
- `SalesOrderResponseCustomerViewTest`: unaffected (no new order fields).

## Definition of done
V89 + domain (`CreditNote` discount/restocked/idempotency; `CounterReturnService`) + the two
endpoints + `?channel=` + receipt barcode + CN PDF discount row + suites above green;
`spotless:apply`; story committed on `165_feat/counter-return`; `CLAUDE.md` gains the two
endpoints and the `?channel=` filter; the frontend pair is
`frontst/stories/127_st_refund_from_receipt.md`.
