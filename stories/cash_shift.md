# Slice: The cash shift — open, sell, pay in/out, close against the ledger

> Item 4 of the owner's gap table (*"Shift open/close with expected versus counted cash — Loyverse
> shifts — every cash tender and refund is already in the ledger — M"*), after
> [`product_cost_and_margin.md`](./product_cost_and_margin.md) (V92),
> [`stocktake_count.md`](./stocktake_count.md) (V93) and [`reorder_point.md`](./reorder_point.md)
> (V94). Implements the sentence that has sat in
> [`sys-analysis/outbound/transaction.md`](../sys-analysis/outbound/transaction.md) since the
> ledger was designed — *"One cash drawer entry = one Transaction"* — by giving those entries the
> one thing they lack: which drawer-day they belong to. **Branch `176_feat/cash-shift` off
> `master`, migration V95.** Frontend pair: `frontst/stories/139_st_cash_shift.md`.
>
> **The owner is often the cashier.** This slice is designed for a shop of one first: a shift opens
> itself on the first counter sale, nothing blocks selling unless the org switches the gate on, the
> owner closes their own count with no second person, and the report reads as a daily cash diary.
> Multi-staff controls exist as switches and role rules, never as ritual.

---

## Goal

A `cash_shift` that answers, for one drawer-day, *how much cash should be in the drawer, and is
it?* — with the expected figure **derived from ledger rows that already exist** and only three
human inputs: the starting float, the counted cash, and any pay-in/pay-out with a reason.

Done means: at close, the counter shows *expected*, *counted*, *over/short* and *take EGP X to the
bank*; every cash tender, change hand-back and cash refund of the day is attributable to that
shift; a shift closes once and is corrected forward, never edited; and none of it costs a sole
owner a single extra tap on an ordinary day.

---

## What exists, what is missing

- Every drawer event is already a `payment_transaction`: the in-store tender
  (`PaymentService.recordInStorePayment`, provider `CASH` / `INSTAPAY_IN_STORE`, CREDIT, VERIFIED
  on the spot), the counter change (`RefundService.createExecutedChangeInTx`, `CASH` DEBIT) and
  the cash counter return (`RefundService.executeInTx`, `CASH` DEBIT, via
  `CounterReturnService`). All inside the sale's or return's own transaction, all timestamped.
- The receipt printer (`escpos_receipt.md`) prints any `SlipModel`; the org has per-org knobs on
  `org` (`refund_approval_threshold`, `order_ttl_minutes`, `tax_rate`) edited through
  `UpdateOrgRequest` by OWNER.
- Missing: nothing groups those rows into a day. There is no float, no count, no pay-out with a
  reason, no "expected cash", and the drawer the printer now pops is never reconciled.

---

## Why this shape

### Derived, never typed

`expected_cash` is a sum over stamped ledger rows plus movements. The cashier types the float and
the count; the system says what should be there. A shift that let someone type "cash sales" would
be a notebook, not a control — and for the sole owner it would be busywork.

### A stamp on the transaction, not a time window

Each drawer transaction gets `cash_shift_id` **at write time**, inside the sale's / return's
transaction, from the org's open shift. Deriving membership later from timestamps would break the
moment a shift is closed a minute late or a refund is executed from the queue the next morning.
The stamp is the truth; the timestamps stay what they are.

What is stamped — *everything that touches the drawer, plus the counter's InstaPay so the slip
totals the counter's receipts*:

| row | provider / direction | stamped? | on the cash line? |
|---|---|---|---|
| in-store tender | `CASH` CREDIT | yes | + cash sales |
| in-store tender | `INSTAPAY_IN_STORE` CREDIT | yes | no — InstaPay total |
| counter change | `CASH` DEBIT | yes | − change given |
| cash refund (counter return, or a desk credit note executed as cash) | `CASH` DEBIT | yes | − cash refunds |
| online transfer reconciled at the desk | `INSTAPAY_MANUAL` | no | — |
| transfer refund executed | `INSTAPAY_MANUAL` DEBIT | no | — |

### One open shift per org, auto-opened

v1 models **one counter per org**. A partial unique index (`org_id WHERE closed_at IS NULL`)
makes a second open shift impossible by construction; a `register` column is the future seam for
a second till and is deliberately not added now.

**Auto-open.** When a counter sale (or return) needs a shift and none is open, the same transaction
opens one: `starting_cash` = the last closed shift's `counted_cash` (the float that was left), else
`0`; `opened_by` = the actor; `auto_opened = true` so the client can offer *"fix the float"* once.
This is the sole owner's ordinary day: sell, and the shift is simply there.

**The gate.** `org.shift_required` (default **false**, OWNER-edited): when true, a counter sale or
return with no open shift is refused — `409` with code `SHIFT_REQUIRED` in the envelope, the way
`APPROVAL_REQUIRED` rides its 403 — and nothing is written. The client turns that into *"Open a
shift to sell"*. Off by default because a shop of one has nobody to gate.

### Close once, correct forward

`close` freezes `expected_cash`, `counted_cash`, `closed_at`, `closed_by`; the difference is
`counted − expected` (positive = over). A closed shift takes no movements and cannot be reopened;
a wrong count is answered by a movement with a reason on the next shift. A shift that was never
closed stays open — the next sale carries on under it and the client says so. **Nothing ever
invents a count at midnight.**

### Who may do what

| action | STAFF | MANAGER / OWNER |
|---|---|---|
| open, pay in/out, close **their own** shift | yes | yes |
| fix the float on an auto-opened shift they own | yes | yes |
| close a shift **someone else** opened | 403 | yes |
| read shifts | VIEWER+ | yes |
| `shift_required` knob | — | OWNER |

"Their own" = `opened_by == actor`. An OWNER ringing sales is the owner of the shift they opened;
the second-person rule only exists once a second person does.

### The slip

`GET /shifts/{id}/slip.escpos` builds a `SlipModel` (title `SHIFT`, meta: Opened / Closed / By,
totals: Starting cash · Cash sales · Change given · Cash refunds · Pay-ins · Pay-outs · **Expected**
· Counted · **Over/short**, then InstaPay · Receipts · Discounts; no lines, no barcode) and paints
it through the raster pipeline — one painter, a second slip. Loyverse prints the same at close.

---

## Data model — V95

```sql
CREATE TABLE cash_shift (
  id             UUID PRIMARY KEY,
  org_id         UUID NOT NULL REFERENCES org(id),
  opened_by      UUID NOT NULL REFERENCES app_user(id),
  opened_at      TIMESTAMPTZ NOT NULL,
  auto_opened    BOOLEAN NOT NULL DEFAULT false,
  starting_cash  NUMERIC(14,2) NOT NULL CHECK (starting_cash >= 0),
  closed_by      UUID REFERENCES app_user(id),
  closed_at      TIMESTAMPTZ,
  counted_cash   NUMERIC(14,2) CHECK (counted_cash >= 0),
  expected_cash  NUMERIC(14,2),                 -- frozen at close
  note           TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_cash_shift_close CHECK (
    (closed_at IS NULL AND closed_by IS NULL AND counted_cash IS NULL AND expected_cash IS NULL) OR
    (closed_at IS NOT NULL AND closed_by IS NOT NULL AND counted_cash IS NOT NULL AND expected_cash IS NOT NULL))
);
CREATE UNIQUE INDEX ux_cash_shift_open ON cash_shift (org_id) WHERE closed_at IS NULL;
CREATE INDEX ix_cash_shift_org_opened ON cash_shift (org_id, opened_at DESC);

CREATE TYPE cash_movement_kind AS ENUM ('PAY_IN', 'PAY_OUT');
CREATE TABLE cash_movement (
  id            UUID PRIMARY KEY,
  org_id        UUID NOT NULL REFERENCES org(id),
  shift_id      UUID NOT NULL REFERENCES cash_shift(id),
  kind          cash_movement_kind NOT NULL,
  amount        NUMERIC(14,2) NOT NULL CHECK (amount > 0),
  reason        TEXT NOT NULL CHECK (length(reason) BETWEEN 1 AND 200),
  recorded_by   UUID NOT NULL REFERENCES app_user(id),
  recorded_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_cash_movement_shift ON cash_movement (shift_id, recorded_at);

ALTER TABLE payment_transaction ADD COLUMN cash_shift_id UUID REFERENCES cash_shift(id);
CREATE INDEX ix_payment_transaction_shift ON payment_transaction (cash_shift_id)
  WHERE cash_shift_id IS NOT NULL;

ALTER TABLE org ADD COLUMN shift_required BOOLEAN NOT NULL DEFAULT false;
```

No backfill: rows before V95 belong to no shift, and the first shift starts clean.

---

## Application & wire

### Service — `CashShiftService`

```
Shift        open(orgId, startingCash, note, actor)                 // 409 SHIFT_ALREADY_OPEN
Shift        currentForCounterInTx(txDsl, orgId, actor)             // the stamp source (below)
Shift        setStartingCash(orgId, shiftId, startingCash, actor)   // open + own (or MANAGER)
Movement     addMovement(orgId, shiftId, kind, amount, reason, actor)
Closed       close(orgId, shiftId, countedCash, note, actor)        // freezes; 409 if closed
Optional<ShiftView> current(orgId)                                  // with live totals
ShiftView    get(orgId, shiftId)                                    // totals + movements
ShiftPage    list(orgId, page, size)                                // newest first
SlipModel    slipModel(orgId, shiftId)                              // for the raster
```

`currentForCounterInTx` is the one call the money paths make, **inside their transaction, before
any row is written**: returns the open shift, locking its row (`FOR UPDATE`) so two phones ringing
at once stamp the same shift; when none is open — `shift_required` → throw `ShiftRequiredException`
(409 `SHIFT_REQUIRED`); else insert the auto-opened shift and return it. Callers:
`PaymentService.recordInStorePayment` (both providers), `RefundService.createExecutedChangeInTx`,
and `RefundService.executeInTx` **when the method is `CASH`** — all set `cash_shift_id` on the
transaction they create. `PaymentTransaction` gains a `cashShiftId` (nullable) carried through
`createClaimed` / `createVerifiedDebit` / `rehydrate` and the repository.

**Totals** (`ShiftTotals`, computed in one query over the stamped rows + movements, in
`ReportRepository` style — jOOQ, NUMERIC kept NUMERIC):

```
cash_sales     = Σ amount  WHERE provider = CASH AND direction = CREDIT AND status = VERIFIED
change_given   = Σ amount  WHERE provider = CASH AND direction = DEBIT  AND provider_ref LIKE 'CASH-%' AND refund.notes = 'counter change'
cash_refunds   = Σ amount  WHERE provider = CASH AND direction = DEBIT  (the rest)
instapay_total = Σ amount  WHERE provider = INSTAPAY_IN_STORE AND direction = CREDIT
pay_in / pay_out = Σ movement.amount by kind
receipts       = COUNT(DISTINCT sales_order_id) over stamped CREDIT rows
discounts      = Σ sales_order.discount_total over those orders
expected_cash  = starting_cash + cash_sales − change_given − cash_refunds + pay_in − pay_out
difference     = counted_cash − expected_cash          (closed only)
```

Change vs refund are separated by the refund row the DEBIT references (`counter change` notes on
the change refund — the existing marker), joined through `refund.payment_transaction_id`. Both
reduce the drawer the same way; the slip shows them apart because the owner reads them apart.

### Endpoints — `/api/orgs/{orgId}/shifts`, staff plane

| method · path | role | body → response |
|---|---|---|
| `GET /current` | VIEWER | `200 ShiftView` (live totals) · `204` when none |
| `POST /` | STAFF | `{starting_cash, note?}` → `201 ShiftView` · `409 SHIFT_ALREADY_OPEN` |
| `PATCH /{id}` | STAFF (own) / MANAGER | `{starting_cash}` → `200` · `409` closed · `403` not own |
| `POST /{id}/movements` | STAFF (own) / MANAGER | `{kind, amount, reason}` → `201 Movement` · `409` closed |
| `POST /{id}/close` | STAFF (own) / MANAGER | `{counted_cash, note?}` → `200 ShiftView` (frozen) · `409 SHIFT_CLOSED` · `403` not own |
| `GET /` | VIEWER | `?page&size` → `{items: ShiftView[], total}` newest first |
| `GET /{id}` | VIEWER | `ShiftView` + `movements[]` |
| `GET /{id}/slip.escpos` | VIEWER | `?width=576\|384` → octet-stream (`escpos_receipt.md` envelope) |

`ShiftView`: `{id, status: OPEN|CLOSED, opened_by {id, name}, opened_at, auto_opened,
starting_cash, closed_by?, closed_at?, counted_cash?, expected_cash, difference?, note?, totals:
{cash_sales, change_given, cash_refunds, pay_in, pay_out, instapay_total, receipts, discounts}}`
— money as bare scale-2 numbers like every other DTO. `expected_cash` on an OPEN shift is live;
on a CLOSED one it is the frozen column.

`org.shift_required` rides `OrgResponse` and `UpdateOrgRequest` (OWNER), the way
`refund_approval_threshold` does.

---

## Authorization

Staff plane only, org-scoped. `ShiftRequiredException` and the "not your shift" 403 are decided in
the service from `actorId` + the caller's `isManagerOrAdmin` (the `counter_discount.md`
precedent), never in the handler.

---

## Out (deferred)

- A second register (`register` column), transfers between tills, safe drops as a first-class
  kind (a `PAY_OUT` with reason *bank* covers it), employee timecards, card-terminal batch
  reconciliation, a `SHIFT_CLOSED` notification to the owner (a report, not news), and any edit of
  a closed shift.
- Backfilling pre-V95 transactions into synthetic shifts.

---

## Tests

`CashShiftIT` (the `CounterReturnIT` harness):
- **Auto-open**: a cash sale with no shift → one OPEN shift, `auto_opened`, `starting_cash 0`,
  `opened_by` the cashier; the tender's transaction carries its id. A second sale stamps the same
  shift (no second row).
- **The float carries**: close with `counted 300` → the next day's first sale auto-opens with
  `starting_cash 300`.
- **Expected math**: float 200; sales cash 150 + 50-with-change-10 (tender 60); return 30 cash;
  pay-in 100; pay-out 80 → `expected = 200 + 210 − 10 − 30 + 100 − 80 = 390`; close counted 385 →
  `difference −5`, frozen; `receipts 2`; an InstaPay sale in the same shift raises
  `instapay_total`, not `cash_sales`.
- **Gate**: `shift_required` + no open shift → `ShiftRequiredException`, no order, no
  transaction, stock intact; open one → the same sale succeeds.
- **One open per org**: `open` while open → 409; the partial index holds under a raced insert.
- **Close once**: close → 409 on a second close; a movement after close → 409.
- **Ownership**: STAFF B closing STAFF A's shift → 403; MANAGER → 200; OWNER closing their own →
  200 with no extra ceremony.
- **Not stamped**: an online transfer reconciled during the shift and a transfer refund executed
  from the queue carry no `cash_shift_id`; a desk credit note executed as **cash** does.
- **List/detail**: newest first, `difference` present only on closed rows, movements in order.
- Regression green: `InStoreSaleIT`, `SplitTenderIT`, `CounterReturnIT`, `CreditNoteRefundIT`.

`ShiftTotalsTest` (unit, the SQL-free arithmetic + the change/refund split);
`CashShiftHandlerTest` (mocked service: roles per the table, the 409 code in the envelope, the
slip route's octet-stream); `DocumentRenderServiceTest.renderShiftSlipEscpos_*` (the rows in
order, `Over/short` signed, bands tile).

## Definition of done

V95 + domain (`CashShift`, `CashMovement`, `PaymentTransaction.cashShiftId`) + repositories +
`CashShiftService` + the three stamp sites + handler/DTOs + `org.shift_required` + the slip +
the suites above green; `spotless:apply`; story committed on `176_feat/cash-shift`; `CLAUDE.md`'s
in-store bullet gains the shift sentence; the frontend pair `frontst/stories/139_st_cash_shift.md`
ships after this merges (against the old API its shift chip 404s and hides — degraded, not broken).
