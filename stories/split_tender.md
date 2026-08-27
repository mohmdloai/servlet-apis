# Slice: Split tender on an in-store sale — cash + InstaPay settle one ticket

> Loyverse gap #4, after [`capture_walk_in_customer.md`](./capture_walk_in_customer.md) (V87),
> [`counter_discount.md`](./counter_discount.md) (V88) and [`counter_return.md`](./counter_return.md)
> (V89). Implements the design that has sat in
> [`sys-analysis/outbound/payment.md` §Split tender (in-store)](../sys-analysis/outbound/payment.md)
> since the payment model was written, with **one stated deviation** (rule 4, below). **Branch
> `166_feat/split-tender` off `master`, no migration** — the model already fits.

---

## Goal

One sale, more than one tender. A customer's InstaPay transfer lands short and they top up the
rest in cash; or they simply prefer to split. The cashier keys both amounts, taps once, and the
books show two money events against one invoice — the same order, the same receipt, the same
change rule.

Done means:
- `POST /sales-orders` (`IN_STORE`) with `payments: [{InstaPay 450}, {Cash 100}]` on a 500.00 sale →
  **two** `payment_transaction` rows (`instapay_in_store` with its reference, `cash`), **two**
  `payment` rows, **two** allocations against the **one** invoice (PAID), order CLOSED, change
  **50.00** handed back as an EXECUTED cash refund drawn from the **cash** payment.
- The same body with `450 + 40` → **400** ("underpaid"), nothing written — pay-full-or-cancel is
  untouched; split relaxes *how many* tenders, not *how much*.
- Every existing single-tender body (`payment: {…}`) is byte-identical in behaviour.

---

## What exists, what is missing

`payment.md` already answered the model question: each tender is one real-world money event, so
it is one `PaymentTransaction` (own `provider_ref`) and one `Payment` (1:1), all with the same
`sales_order_id`; N payments per order is the online two-transfer reality; and
`InvoiceService.issueForFulfillment` **already allocates FIFO across all of the order's unallocated
payments** up to the invoice total. So this is a checkout-orchestration slice. What the code
actually does today:

1. `placeInStoreSale` takes **one** `PaymentInput` and records **one** payment
   (`PaymentService.recordInStorePayment`), then **guards** the allocator's result: *"in-store sale
   expected at most one consumed payment"* — the seam this slice was reserved for.
2. Change is drawn from **that one** payment (`RefundService.createExecutedChangeInTx`, method
   always CASH, no approval gate: net money-in is exactly the invoice total).
3. The response carries a single `payment` block; the 80 mm receipt sums every payment into one
   `Tendered` line; the counter return reads *"the sale's tender"* as `payments.get(0)`'s provider
   ([`CounterReturnService.tenderOf`](../service/src/main/java/com/loai/inventory/service/CounterReturnService.java)).

---

## Why this shape — the seven rules, and where this slice departs

The rules in `payment.md` are adopted verbatim except one:

- **Rule 1 — coverage.** `Σ amounts ≥ grand_total` (the *discounted* grand, V88), every amount `> 0`;
  short → 400 with the existing "underpaid" message. Unchanged.
- **Rule 2 — one Transaction + one Payment per tender**, providers `CASH` / `INSTAPAY_IN_STORE`
  (`INSTAPAY_MANUAL` keeps its in-store 400). An InstaPay tender carries its real reference; a
  **refless** tender gets a synthesised ref keyed on the order's idempotency key so the global
  `(provider, provider_ref)` UNIQUE still fails a *retried* checkout — with a per-tender ordinal
  where two refless tenders of one sale would otherwise collide: `INSTAPAY_IN_STORE-<idem>-1`,
  `-2`. The single cash tender keeps today's `CASH-<idem>` (one cash event per sale, rule 3), so
  the existing retry barrier and its IT are untouched.
- **Rule 3 — fold duplicate cash tenders** into one before recording: two cash amounts at one
  counter moment are one drawer event. Several InstaPay tenders stay distinct (distinct transfers).
- **Rule 4 — deterministic FIFO. Deviation:** `payment.md` says *request order*; this slice stamps
  a **canonical** order instead — **InstaPay tenders first (in request order), the folded cash
  tender last** — with strictly increasing `received_at` (`now + index µs`). Why: the residue of an
  overpaid split then sits on the **cash** payment whenever the cash tendered covers it, which is
  the common case (a shopper hands notes; the transfer was exact or short) — so "change in cash" is
  literally a draw on the cash payment and the response's `change` block stays single. Under
  request order the same sale would put the residue on whichever tender the cashier keyed last,
  and a residue on the InstaPay payment reads on the ledger as a refund *of the transfer*. The
  deviation is recorded in `payment.md` by this slice (a one-line amendment to rule 4).
- **Rule 5 — change on overpay, in cash regardless.** After allocation, **every** payment left with
  `unallocated > 0` gets an EXECUTED cash change refund inside the txn (`createExecutedChangeInTx`
  already draws CASH from whatever payment holds the residue). With the canonical order that is
  one refund in every case where cash ≥ residue; when the *transfer itself* overpaid (InstaPay 520
  + cash 30 on 500) it is two — 20 off the transfer's payment, 30 off the cash — and the response
  says so. No approval gate, for the reason `payment.md` gives.
- **Rule 6 — atomicity.** One txn; any failure rolls back every tender. The existing barriers
  (order `(org_id, idempotency_key)` UNIQUE; per-transaction `(provider, provider_ref)` UNIQUE)
  both still fire on a retry.
- **Rule 7 — cap:** more than **4** tenders → 400 naming the cap. Beyond that it is an installment
  plan, out of scope.

**The counter return on a split sale** (`counter_return.md` refunds "in the sale's tender"): the
refund is **cash when the note total fits the cash the drawer still holds for this sale, else a
pending transfer**. Not "cash tendered": the counter change and every earlier return are `refund`
rows with `method = CASH` against this sale, and each one spent some of that cash. So

```
cash_refundable = Σ cash tenders − Σ CASH refunds on the sale that are not CANCELLED
                  (the counter-change refunds against its payments
                   + the credit-note-backed refunds against its invoice's notes)
note.total ≤ cash_refundable  ⇒  CASH, executed now
otherwise                     ⇒  INSTAPAY_MANUAL, PENDING (the merchant sends the transfer)
```

Worked: `450 cash + 50 InstaPay`; a first return of 400 goes out in cash (`400 ≤ 450`); a second
return of 90 is a transfer (`450 − 400 = 50 < 90`). And `150 cash + 450 InstaPay` on a 500 sale:
change 100 leaves `cash_refundable = 50`, so a 90 return is a transfer even though 150 in notes
crossed the counter. One refund, one rule, recomputed inside the return's transaction (the
invoice lock serialises concurrent returns, so two returns cannot both read the same headroom).
A cash-only sale reduces to today's behaviour (`cash_refundable ≥` whatever can still be credited,
since Σ notes ≤ grand) and an InstaPay-only sale to `0` — always a transfer. The preview exposes
`cash_refundable` so the sheet can say which way a given quantity will go before the tap.
Rejected: letting the manager choose (more UI for a case the rule already answers sensibly), and
"always transfer" (a customer who paid mostly cash waits for a transfer).

---

## Data model

**None.** No migration; V89 stays the highwater. `payment_transaction.received_at`/`payment.received_at`
already carry microsecond precision (`TIMESTAMPTZ`), which the canonical order relies on.

---

## Application & wire

### Request — `POST /api/orgs/{orgId}/sales-orders`, `channel: IN_STORE`
```json
{
  "channel": "IN_STORE",
  "lines": [ { "product_id": "<uuid>", "quantity": 5 } ],
  "payments": [
    { "provider": "INSTAPAY_IN_STORE", "amount": 450.00, "provider_ref": "IPN-77812" },
    { "provider": "CASH", "amount": 100.00 }
  ]
}
```
- `payments` (1–4) **or** the existing single `payment` block (mapped to a one-element list; both
  present → 400). `amount` is **required** on every split tender; the single-tender shorthand keeps
  its "no amount ⇒ exact" default.
- Validation, cause-naming 400s in `validateInStoreInputs`: empty list; > 4; unknown/`INSTAPAY_MANUAL`
  provider; `amount ≤ 0`; `Σ < grand_total` (computed against the discounted grand, so the check
  moves *inside* the txn after `buildDraftOrder`, exactly where the single tender's check lives).

### Service
- `PaymentInput` → `List<PaymentInput> tenders` on `placeInStoreSale` (the handler wraps a single
  `payment` into a list; the 13 IT call sites pass `List.of(...)`).
- `normaliseTenders(List<PaymentInput>)`: fold cash into one (sum; a real ref on any cash tender is
  kept, else synthesised), order InstaPay-first/cash-last, assign ordinals. Pure, unit-tested.
- Record each tender through `recordInStorePayment` with `received_at = now.plusNanos(1_000 × i)`
  (a new `receivedAt` parameter; the existing callers pass `now`). The milestone stamp
  (`FIRST_PAYMENT`) is idempotent (`ON CONFLICT DO NOTHING`) so N tenders stamp once.
- The allocator's guard goes; `InStoreSale` becomes `payments: List<Payment>` (the allocator's fresh
  copies, in canonical order) and `changeRefunds: List<RefundService.Executed>`.
- Change: for each payment with `unallocated > 0` (in canonical order) → `createExecutedChangeInTx`.

### Response — `201`
```json
{
  "order": { … },
  "invoice": { …, "paid_amount": 500.00 },
  "payments": [
    { "id": "…", "provider": "INSTAPAY_IN_STORE", "provider_ref": "IPN-77812", "status": "ALLOCATED", "amount": 450.00, "unallocated_amount": 0.00 },
    { "id": "…", "provider": "CASH", "provider_ref": "CASH-<idem>", "status": "ALLOCATED", "amount": 100.00, "unallocated_amount": 0.00 }
  ],
  "payment": { "id": "…", "status": "ALLOCATED", "amount": 450.00, "unallocated_amount": 0.00 },
  "change": { "amount": 50.00, "refund_id": "…", "status": "EXECUTED",
              "refunds": [ { "refund_id": "…", "amount": 50.00, "status": "EXECUTED", "payment_id": "…" } ] }
}
```
- `payments[]` is the new truth (provider + ref come from each transaction). `payment` is **kept
  for one release** as the first element so an admin build that predates the frontend pair still
  renders; the pair switches to `payments[]` and the field is retired with it.
- `change.amount` is the **sum**; `refund_id`/`status` describe the first refund (compat);
  `refunds[]` lists each. Omitted when no residue.

### Receipt (80 mm)
When the sale has more than one tender, the single `Tendered` line becomes one line per tender —
`Cash 100.00 EGP` / `InstaPay 450.00 EGP` — followed by `Tendered` (the sum) and `Change` as
today. A single-tender slip is byte-identical.

### Counter return
- `GET /sales-orders/{id}/returnable` gains `tenders: [{provider, amount}]` and **`cash_refundable`**
  (the formula above, from the ledger — never a cached figure); `refund_mode` is derived from it
  against what can still be credited (`grand − Σ live notes`): `IMMEDIATE_CASH` when
  `cash_refundable ≥ remaining_creditable` (any return fits in cash — every cash-only sale),
  `PENDING_TRANSFER` when `cash_refundable = 0` (every InstaPay-only sale, or a split whose cash
  is spent), or **`SPLIT`** (the sheet decides per quantity: cash iff `total ≤ cash_refundable`).
- `POST /sales-orders/{id}/return` recomputes `cash_refundable` under the invoice lock and applies
  the same rule authoritatively: `note.total ≤ cash_refundable ⇒ CASH executed now, else
  INSTAPAY_MANUAL PENDING`. The response's `refund.method` is the answer; nothing on the client
  guesses it.

## Authorization
Unchanged: the whole checkout, change refunds included, is the **STAFF** sale action (a discount
inside it still needs MANAGER+, V88).

## Out (deferred)
- More than one cash tender per sale (folded by rule 3); more than 4 tenders; installments.
- A tender-level void/edit after the sale (the path out is the counter return).
- Card/other providers — the enum has none.
- `payment.md` §Underpaid "chase" (a second tender *after* the checkout): still the documented
  admin path, not this slice.

---

## Tests

`api/src/test/java/.../sale/SplitTenderIT.java` (the `InStoreSaleIT` harness):
- **Exact split** `InstaPay 300 + Cash 200` on 500: two VERIFIED+MATCHED CREDIT transactions
  (providers as sent, the InstaPay one with its ref), two payments `ALLOCATED` with `unallocated 0`,
  two allocations summing to the invoice grand, invoice PAID `paid_amount 500`, order CLOSED
  `prepaid 500`, stock moved once, `FIRST_PAYMENT` stamped once; `payments()` in canonical order.
- **Overpaid on the cash tender** `450 + 100` on 500: residue on the cash payment, **one** change
  refund CASH 50 EXECUTED with its DEBIT cash txn; cash payment `refunded_amount 50`, InstaPay
  payment untouched; `changeRefunds().size() == 1`.
- **Overpaid on the transfer** `InstaPay 520 + Cash 30` on 500: **two** change refunds (20 off the
  transfer's payment, 30 off cash), both CASH; `change.amount 50`.
- **Canonical order beats request order**: `[Cash 100, InstaPay 450]` keyed cash-first → identical
  ledger to the `[InstaPay, Cash]` body (payments ordered InstaPay then cash; residue on cash).
- **Short** `450 + 40` → 400 "underpaid", nothing written (order count 0, no txn, stock intact) —
  and short against the **discounted** grand (V88) is judged the same way.
- **Fold**: `Cash 100 + Cash 200 + InstaPay 200` → one cash payment 300 with ref `CASH-<idem>`.
- **Refless InstaPay ×2** → refs `INSTAPAY_IN_STORE-<idem>-1` / `-2`; **cap**: 5 tenders → 400.
- **Retry**: the same body + key twice → 409 on the order barrier; a *different* key with the same
  InstaPay ref → 409 on the transaction barrier, nothing written.
- **Both blocks** (`payment` and `payments`) → 400; the single `payment` shorthand still records
  one payment (regression via `InStoreSaleIT`, untouched).
- **`CounterReturnIT` +4** (the headroom rule):
  - `450 InstaPay + 100 cash`: a 90.00 return → CASH EXECUTED; a 300.00 return → INSTAPAY_MANUAL
    PENDING; the preview says `cash_refundable 100.00`, `refund_mode SPLIT`.
  - **Earlier returns spend the headroom**: `450 cash + 50 InstaPay`, return 400 → CASH; then a
    further 90 → PENDING transfer (`50 < 90`), and the preview now says `cash_refundable 50.00`.
  - **Counter change spends it too**: `150 cash + 450 InstaPay` on 500 (change 100) → the preview
    says `cash_refundable 50.00`; a 90.00 return → PENDING transfer.
  - **A cancelled cash refund gives it back**: cancel the pending transfer above, then a 40.00
    return → CASH (`50 ≥ 40`); a CANCELLED row never counts.
  - Regression: a cash-only sale's returns are always CASH (`IMMEDIATE_CASH`), an InstaPay-only
    sale's always PENDING (`PENDING_TRANSFER`) — the existing `CounterReturnIT` cases untouched.
- `PaymentServiceTendersTest` (unit): `normaliseTenders` fold/order/ordinal cases.
- `DocumentRenderServiceTest`: `renderReceipt_printsOneLinePerTenderWhenSplit` (+ the single-tender
  slip asserts no provider lines).
- Regression green: `InStoreSaleIT`, `CounterDiscountIT`, `CounterReturnIT`, `CreditNoteRefundIT`.

## Definition of done
Service + handler/DTOs + receipt + return preview rule + `payment.md` rule-4 amendment + the suites
above green; `spotless:apply`; story committed on `166_feat/split-tender`; `CLAUDE.md`'s in-store
bullet gains the `payments[]` sentence; the frontend pair is `frontst/stories/129_st_split_tender.md`.
