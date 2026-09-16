# Slice: Goods receipt — the supplier, the cost that was actually paid, and the entry that finally credits 2000 honestly

> **Inbound slice 1 of 5.** `sys-analysis/inbound/README.md` has held procurement as "deferred,
> not in v1" since April, and its three revisit triggers are all *merchant-side* ("an org asks for
> supplier tracking"). A fourth condition appeared on **2026-09-15** that the README could not
> anticipate: the general ledger shipped (V101), and its chart already carries **`2000` "Supplier
> purchases (unbilled)"** — a liability every `RESTOCK` credits (`LedgerRepositoryImpl:293`) and
> **nothing anywhere debits**. `stories/general_ledger.md` §Known limits says so itself: *"a
> restock credits 2000 at the product's current cost; supplier bills (inbound context, deferred)
> will clear it."* So the books now grow a liability that cannot be settled, and the balance sheet
> the admin app renders is wrong by that amount and rising.
>
> This slice does **not** build accounts payable — that is slice 2. It builds the two things the
> other four slices stand on: **a supplier**, and **a goods receipt that knows what the goods
> actually cost**. Its second, quieter job is to fix the number the ledger is already using: today
> a restock is valued at whatever `product.cost_price` happens to say, and for a shop that never
> typed a cost, at nothing at all (`ledger_skip` `UNCOSTED` — perfdb's largest org has 24 064 such
> rows). A receipt carries the price on the delivery note, so the movement is valued by the
> document that caused it.
>
> **Branch `197_feat/goods-receipts` off `master`, migration V102.** Frontend pair:
> `frontst/stories/163_st_goods_receipt.md` (branch `166_feat/goods-receipts`), which ships after
> this. Re-verify both counters at PR time.

---

## Goal

1. **A supplier is a record, exactly the way a customer is one.** `supplier` mirrors `customer`'s
   shape and its rules — org-scoped, no password, no portal, no authentication ever — so "who did
   I buy this from" has an answer and slices 2–4 have a counterparty to hang AP on.
2. **A goods receipt records what arrived and what it cost, and moves the stock in one
   transaction.** N lines of `{product, quantity, unit_cost}`; each line is one `+stock`
   `inventory_log` row stamped with **the cost on the delivery note**, not the product's standing
   figure; the product's `cost_price` is brought up to it so the *next* sale's COGS is right.
3. **The ledger gets correct, and gains no new template.** A receipt posts *nothing of its own* —
   its lines' stock movements are already a posting (`STOCK`/`MOVED` → DR `1200` Inventory / CR
   `2000` Purchases unbilled). All this slice changes is the amount: a real one instead of a stale
   one or none. Slice 2's supplier bill is what debits `2000`.

Done means: a merchant picks a supplier, keys the delivery note, and the shop's stock, the
product's cost, the movement ledger, `GET /ledger/trial-balance` and `uncosted_stock_moves` all
move together off one document — and the receipt can be voided when it was keyed wrong.

---

## What exists, what is missing

- `POST /api/orgs/{orgId}/inventory/{productId}/restock {qty}` (STAFF, optional
  `Idempotency-Key` since V50) — the only way stock has ever been added. It takes **no price, no
  supplier and no document**: `InventoryService.restock(orgId, productId, qty, actor, key)`.
- `inventory_log` (V9 → V101) carries deltas, running balances, reason, actor, impersonator,
  `idempotency_key`, and since V101 **`unit_cost`** — stamped inside
  `InventoryLogRepositoryImpl.baseInsert` by a subselect of `product.cost_price` at insert time,
  with **no way for a caller to supply one**. That subselect is the single line this slice has to
  open up, and it is the only existing write site it touches at all.
- `product.cost_price` (V92) — nullable, MANAGER-plane, "not costed" ≠ 0.00. Every sale freezes it
  onto `sales_order_line.unit_cost` at placement, and `/reports/profit` and the ledger's COGS both
  read that frozen figure. So **a cost typed today prices tomorrow's sales, never yesterday's** —
  which is exactly why a receipt must write it, and why it must not try to restate history.
- The ledger (V101): the chart holds `1200` Inventory, `2000` Purchases (unbilled), `5000` COGS,
  `5100` Inventory adjustments. The `STOCK`/`MOVED` template posts every value-moving
  `inventory_log` row, keying the two accounts off `reason` and taking `abs(stock_delta) *
  unit_cost`; an uncosted row is recorded in `ledger_skip` as `UNCOSTED` and counted on
  `GET /ledger/health`, never priced at zero.
- `customer` (V2 → V81): the CRM-record precedent this slice mirrors — `name` with the `und-x-icu`
  collation and a generated `name_search = fold_search(name)` + trigram index (V62), `phone` kept
  verbatim beside a derived `phone_e164` (V79), email normalized by `Text.normalizeEmail`.
  CLAUDE.md's rule — *"Customer = CRM record: customers do not authenticate"* — is the rule a
  supplier inherits wholesale.
- `invoice_number_counter` (V30): per-org per-year, advanced `SELECT … FOR UPDATE` **inside the
  issuing transaction** so a rollback un-burns the number. The numbering pattern to copy.
- `sales_order (org_id, idempotency_key)` — the required-key, replay-or-409 pattern for a call
  that moves stock.
- `AuthzHelper.hasManagerAuthority(ctx, orgId)` (promoted by V92, reused by V88's counter
  discount) — the one predicate for "may see and set cost".

Missing, therefore: two tables and a counter, one parameter on one shared insert, and a route.
**No new ledger template, no new chart account, no new notification type, no new rate-limit
bucket.**

---

## Why this shape

### The receipt posts nothing; its stock movements already do

The tempting design is a `GOODS_RECEIPT`/`RECEIVED` posting template: DR Inventory / CR Purchases
for the receipt's total. It would be **double counting**, because each line already writes an
`inventory_log` row and V101's `STOCK` template already posts each of those as DR `1200` / CR
`2000`. Two readings of one event, differing by rounding, is precisely the class of defect the
ledger's health cross-checks exist to catch — no reason to manufacture one.

So the receipt is a *document over movements*, not a second source of truth. Its `total_cost` is
frozen on the header for slice 2 to match a bill against, and is equal to Σ (line quantity ×
line unit cost) by construction — the same identity `sales_invoice` carries and the same guard
shape (`INVOICE_GUARD`) if it ever needs one. The whole ledger change in this slice is that
`unit_cost` on those rows is now a number somebody actually paid.

### A new `StockReason` was considered and rejected; the link is a column

`RECEIPT` as an eighth `stock_reason` value reads well — until you price it. It would post to the
same two accounts as `RESTOCK`, so it buys the ledger nothing, while costing an `ALTER TYPE`, a
codegen, two `CASE` arms in the poster, a frontend label, and a permanent fork in every "how did
stock get here" query. The distinction a reader actually wants — *this came from a supplier on
document GRN-2026-00007* — is carried better by **`inventory_log.goods_receipt_id`**, which is the
exact mirror of `order_id`, and reads back the exact way `sales_order_number` does: batch-loaded
onto the movement-ledger row, one query per page, no N+1. A receipt line is a `RESTOCK` that knows
where it came from.

### The void is in scope, and it makes the template's sign honest

A receipt keyed as 100 instead of 10 must be undoable, and "correct it with an adjustment" is not
good enough here: an `ADJUSTMENT` posts to `5100` Inventory shrinkage, so a typo would land in the
P&L as a loss and leave `2000` overstated forever. `POST /goods-receipts/{id}/void` reverses the
document properly — a negative `inventory_log` row per line, at the **same** `unit_cost`, linked
to the same receipt — and the ledger reverses with it.

That exposes a case the V101 template has never had to handle. It keys the accounts on `reason`
and takes `abs(stock_delta)`, so a **negative** `RESTOCK` row would post DR `1200` / CR `2000`
again — an increase booked for a decrease. It is unreachable today (`InventoryService.validateQty`
refuses a non-positive restock, so no negative `RESTOCK` row exists anywhere), which is why it is
not a live bug; the void makes it reachable, so the `RESTOCK` arm becomes sign-aware in the same
slice, mirroring what the `ADJUSTMENT`/`STOCKTAKE` arm already does:

```
RESTOCK, stock_delta > 0  →  DR 1200 Inventory   / CR 2000 Purchases (unbilled)
RESTOCK, stock_delta < 0  →  DR 2000 Purchases   / CR 1200 Inventory
```

No posted entry changes (no existing row has a negative `RESTOCK` delta), so a `?reset=true`
rebuild over existing data must produce byte-identical entries — asserted, not assumed.

**A void does not restore the product's previous cost.** Nothing knows what the cost was before
this receipt raised it, and `cost_price` is a standing figure the merchant may have edited since;
inventing the old number is worse than leaving the current one, which the merchant can see and
change. Stated in the contract, surfaced in the UI, listed under the limits.

### Receiving is MANAGER, whole — the same gate cost already has everywhere

A receipt *is* a cost document: every line carries a unit cost and the total is what the shop owes
a supplier. V92 settled that cost is MANAGER-plane and `/reports/profit` and `/ledger` are gated
whole rather than field-by-field; a receipt read that had to blank out its own money column would
be a worse version of that decision. So **every `/goods-receipts` route is MANAGER+** (or platform
ADMIN), 403 below, and the frontend renders the honest empty state rather than firing a read.

The **supplier directory is not** — it is a name, a phone and an address, carrying no money at
all, so it mirrors `/customers` exactly: VIEWER reads, STAFF writes, MANAGER deletes. A cashier
who can see that "Zaki Paper" exists learns nothing about what the shop pays for paper.

### The cost write-back is the point, not a side effect

`cost_price` is what the next sale freezes. A receipt that recorded a cost only on its own line
would leave the shop's margin reporting exactly as wrong as it is today, and the merchant would
still be typing costs by hand on the product form. So the receipt sets `product.cost_price` to the
line's unit cost, in the same transaction, on the **last-cost** method — stated plainly in the
contract and in the UI, because a silent cost rewrite is not something a merchant should discover
from a margin report.

Last cost, not weighted average, and the reason is structural rather than a preference: COGS in
this system is **frozen per sale line** at placement. A moving average is only meaningful if the
stock on hand is revalued when it changes, which means restating inventory value and re-costing
open lines — a different machine, with its own reconciliation, that would have to agree with
`/reports/profit` and the ledger to the piastre. Recorded in *Out* so it is chosen deliberately if
it is ever chosen.

### Untracked products are refused, not silently created

A receipt line for a product with no `inventory` row is a **409 naming the product**, nothing
written — the rule `counter_return.md` already set for a restock of an untracked product. The
alternative (auto-initialise) makes a receipt able to create inventory rows as a side effect of a
typo in a product picker, and the remedy is one existing call away (`POST /inventory`).

### The supplier list sorts by name, and that is a deliberate deviation

`org_customer_reads.md` fixed `created_at DESC` *including* under `?q=`, because a customer
directory of thousands is read newest-first and re-sorting while the operator types is
disorienting. A supplier directory is **tens of rows, read alphabetically** — it is the paper list
on the wall. So suppliers sort `name ASC` (the V62 `und-x-icu` collation is already on that column
shape and orders Arabic and Latin properly), `id ASC` as the tiebreak. Same reasoning, opposite
answer; both stated so neither gets "fixed" into the other.

`?q=` copies the customer read's legs verbatim — `fold_search` on both sides of the name (so
`احمد` finds `أحمد`) OR'd with a case-insensitive substring on the normalized email — and
**phone stays unsearchable**, the same pin `search_doesNotMatchPhone` holds for customers.

### One supplier per name, folded

`UNIQUE (org_id, name_search)` — on the *generated folded* column, not on `name`. Two spellings of
one supplier are not two suppliers; they are one AP balance split in half, which is the single
most expensive data-quality defect a procurement module can have, and V62 already computes the
folding for free. A duplicate is a 409 naming the existing supplier.

---

## Contract

### `GET|POST /api/orgs/{orgId}/suppliers` · `GET|PUT|DELETE /api/orgs/{orgId}/suppliers/{id}`

Read **VIEWER**, write **STAFF**, delete **MANAGER** (the `/customers` gates).

`GET ?q=&active=&page=&size=` → `PageResponse` of
`{id, name, phone?, email?, address?, notes?, active, created_at, updated_at}`, **`name ASC, id
ASC`**. `q` = folded name OR email substring; blank/whitespace-only `q` is the unfiltered list,
never a 400. `active=true|false` narrows (absent = both; any other value → 400).

`POST|PUT {name, phone?, email?, address?, notes?, active?}` — `name` required, trimmed, 1–200
chars; `phone` stored verbatim with `phone_e164` derived by the shared `Phone.toE164` at every
ingress (null when unparseable, exactly as for a customer); `email` normalized by
`Text.normalizeEmail`, optional and **not** unique (two suppliers may share an office address).
Duplicate folded name → **409** `"A supplier named … already exists"`. `PUT` is a merge (a null
field is leave-unchanged, the `PUT /api/orgs/{orgId}` convention).

`DELETE` → **204**, or **409** when the supplier is referenced by any goods receipt (the FK
violation mapped to `ConflictException`, the `DELETE /products` precedent) — the remedy named in
the message is `active:false`, which is what the flag is for.

### `GET /api/orgs/{orgId}/suppliers/{id}/receipts?page=&size=`

**MANAGER** (it carries costs, unlike the parent resource). The `customers/{id}/orders`
precedent: a subresource, not a `?supplier_id=` on a route that already answers two shapes.
Newest-first `PageResponse` of lean rows `{id, receipt_number, received_at, line_count,
total_cost, status}`. Unknown supplier → **404** (a path segment names a thing); a supplier with
no receipts → an **empty page**.

### `POST /api/orgs/{orgId}/goods-receipts`

**MANAGER.** `Idempotency-Key` header **required** (400 when missing) — it moves stock.

```json
{
  "supplier_id": "…",
  "received_at": "2026-09-16T10:00:00Z",
  "supplier_reference": "DN-88213",
  "notes": "two boxes short, credited next delivery",
  "lines": [ { "product_id": "…", "quantity": 24, "unit_cost": 12.50 } ]
}
```

- `supplier_id` required and must belong to the org (unknown → 404); an **inactive** supplier is
  a 409 naming it (you can receive from a supplier you have retired only by un-retiring them).
- `received_at` optional, defaults to now; a future instant → 400.
- `supplier_reference` optional free text ≤ 100 (their delivery-note number, **not** ours);
  deliberately **not** unique — slice 2 decides what a duplicate supplier document means.
- `lines` 1–200; `quantity` integer > 0; `unit_cost` ≥ 0 scale-2 (**0.00 is legal and means
  free-of-charge goods**, which post nothing in the ledger — `amount > 0` on `journal_line` — and
  are recorded in `ledger_skip` as `UNCOSTED`, honestly). Duplicate `product_id` across lines →
  400 (merge them; two lines of one product at two costs is a cost question this slice does not
  answer). A product not in the org → 404.

Responses:

- **201** `GoodsReceiptResponse` — `{id, receipt_number, supplier:{id,name}, received_at,
  supplier_reference?, notes?, status:"POSTED", total_cost, lines:[{product_id, product_name, sku,
  quantity, unit_cost, line_total, stock_after}], created_at}`.
- **200** on an `Idempotency-Key` replay — the prior receipt, nothing re-applied.
- **409** same key, different body: `"Idempotency-Key reused with different parameters"`.
- **409** a line's product is untracked: *"… has no inventory record — initialise its stock
  first"*, **nothing written**.
- **403** below MANAGER.

In one transaction: allocate the number → insert header + lines → per line, lock the inventory row
`FOR UPDATE`, `+stock`, insert the `inventory_log` row with `reason = RESTOCK`, `unit_cost =` the
line's cost, `goods_receipt_id =` the receipt → `UPDATE product SET cost_price = line.unit_cost`.

`receipt_number` is **`GRN-YYYY-NNNNN`**, per-org per-year from `goods_receipt_number_counter`,
advanced `FOR UPDATE` in this same transaction so a rollback burns nothing (V30's rule).

**Consequences, none of them new code:** the crossing notifier re-arms for free (a receipt lifts
`available` back above the product's `reorder_point`, so `LOW_STOCK` can fire again on the next
downward crossing — and receiving itself never notifies, because only a sale crosses downward);
the product leaves the `?stock=reorder` and `?stock=out` segments; `ledger_skip` stops growing
`UNCOSTED` rows for that product.

### `GET /api/orgs/{orgId}/goods-receipts?supplier_id=&status=&from=&to=&q=&page=&size=`

**MANAGER.** `PageResponse` of the lean row above plus `supplier:{id,name}`. `status` ∈
`POSTED|VOIDED` (unknown → 400); `from`/`to` half-open ISO-8601 on `received_at` (bare dates and
`from >= to` → 400, the reports' convention); `q` = receipt number or `supplier_reference`
(trimmed, case-insensitive fragment); unknown `supplier_id` → an **empty page** (a query parameter
narrows a set — the opposite of the subresource's 404, and both are right).

**Ordering is `received_at DESC, id DESC` always**, filtered or not — the stated deviation from
the queue-vs-ledger convention, because neither status is a worklist: a receipt is done the moment
it is posted, and a voided one is history. Nobody works a queue of deliveries that already
happened.

### `GET /api/orgs/{orgId}/goods-receipts/{id}` · `POST .../{id}/void`

**MANAGER.** The detail is the full response above, with `voided_at`/`void_reason`/`voided_by_name`
when voided. `POST .../void {reason}` (`reason` required, ≤ 200) → 200 with the voided receipt:
one negative `inventory_log` row per line at the same `unit_cost` and the same `goods_receipt_id`,
under the inventory row lock, in one transaction.

- **409** already VOIDED (idempotent replay is *not* the shape here — a void is an accounting
  event with an author and a reason, and a second one is a mistake worth naming).
- **409** when reversing a line would take `stock_qty` below `reserved_qty` (which covers below
  zero), naming the product and the held units — the `adjust` guard's wording, checked under the
  lock before anything is written. The goods were sold or are held for an order; the remedy is a
  stocktake adjustment, not a void.
- **Never** restores `product.cost_price` (see above), and **never** touches a line's frozen
  `sales_order_line.unit_cost` on sales that already happened.

### Reads that gain a field

- `GET /api/orgs/{orgId}/inventory/{productId}/log` — rows gain **`goods_receipt_id`** and
  batch-loaded **`goods_receipt_number`** (absent on rows with no receipt), exactly as
  `sales_order_number` rides order-linked rows. CLAUDE.md's "null for RESTOCK/ADJUSTMENT/STOCKTAKE"
  line needs the amendment: a `RESTOCK` row may now name a receipt instead of an order.
- `GET /api/orgs/{orgId}/ledger/health` — unchanged in shape; `uncosted_stock_moves` simply falls
  as receipts land. **No new cross-check**: `2000` has no independent app-side figure to compare
  against until slice 2 gives bills a balance.

---

## Migration V102

```sql
CREATE TABLE supplier (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     UUID        NOT NULL REFERENCES org(id),
    name       TEXT        NOT NULL COLLATE "und-x-icu",
    name_search TEXT       GENERATED ALWAYS AS (fold_search(name)) STORED,
    phone      TEXT,
    phone_e164 TEXT,
    email      TEXT,
    address    TEXT,
    notes      TEXT,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX supplier_org_name_idx ON supplier (org_id, name_search);
CREATE INDEX supplier_org_active_name_idx ON supplier (org_id, active, name);

CREATE TABLE goods_receipt (
    id                 UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id             UUID          NOT NULL REFERENCES org(id),
    supplier_id        UUID          NOT NULL REFERENCES supplier(id),
    receipt_number     TEXT          NOT NULL,
    status             TEXT          NOT NULL DEFAULT 'POSTED'
                                     CHECK (status IN ('POSTED', 'VOIDED')),
    received_at        TIMESTAMPTZ   NOT NULL,
    supplier_reference TEXT,
    notes              TEXT,
    total_cost         NUMERIC(14,2) NOT NULL CHECK (total_cost >= 0),
    idempotency_key    TEXT,
    voided_at          TIMESTAMPTZ,
    void_reason        TEXT,
    voided_by          UUID          REFERENCES app_user(id),
    created_by         UUID          REFERENCES app_user(id),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (org_id, receipt_number)
);
CREATE UNIQUE INDEX goods_receipt_idem_idx ON goods_receipt (org_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX goods_receipt_org_received_idx ON goods_receipt (org_id, received_at DESC, id DESC);
CREATE INDEX goods_receipt_supplier_idx ON goods_receipt (org_id, supplier_id, received_at DESC);

CREATE TABLE goods_receipt_line (
    id               BIGSERIAL     PRIMARY KEY,
    org_id           UUID          NOT NULL REFERENCES org(id),
    goods_receipt_id UUID          NOT NULL REFERENCES goods_receipt(id) ON DELETE CASCADE,
    product_id       UUID          NOT NULL REFERENCES product(id),
    quantity         INT           NOT NULL CHECK (quantity > 0),
    unit_cost        NUMERIC(14,2) NOT NULL CHECK (unit_cost >= 0),
    line_total       NUMERIC(14,2) NOT NULL CHECK (line_total >= 0),
    UNIQUE (goods_receipt_id, product_id)
);
CREATE INDEX goods_receipt_line_receipt_idx ON goods_receipt_line (goods_receipt_id);

CREATE TABLE goods_receipt_number_counter (
    org_id   UUID   NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    year     INT    NOT NULL,
    next_val BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (org_id, year)
);

-- The movement's document, the mirror of inventory_log.order_id. Nullable and NOT backfilled:
-- every RESTOCK before V102 was keyed by hand and had no document.
ALTER TABLE inventory_log
    ADD COLUMN goods_receipt_id UUID REFERENCES goods_receipt(id);
CREATE INDEX inventory_log_receipt_idx ON inventory_log (goods_receipt_id)
    WHERE goods_receipt_id IS NOT NULL;
```

No backfill, no data change, nothing dropped. `fold_search` is V62's, already in the schema.
perfdb is hand-migrated V101 → V102 by the standing procedure in `tools/seed/README.md`.

---

## Out (deferred, each with its shape)

- **Supplier bills and accounts payable — slice 2.** A new chart account (`2300` Accounts
  payable), `supplier_bill` + lines against one or more receipts, templates `BILL/RECEIVED`
  (DR `2000` / CR `2300`) and the third `/ledger/health` cross-check (`accounts_payable` vs
  Σ unpaid bills). **This is what finally debits `2000`; until it ships the account keeps
  growing** — the state this slice leaves the books in, stated rather than hidden.
- **Supplier payments — slice 3**, and with them the two collisions the assessment found:
  `GET /payment-transactions` has **no direction filter**, so a money-out row to a supplier would
  appear in the customer reconciliation worklist and in its `summary.money_out`; and a supplier
  paid from the till is today either a `PAY_OUT` movement (posted as owner drawings) or a cash
  DEBIT that `CashShiftRepositoryImpl.totals` classifies as a **cash refund**. Neither is a
  blocker for this slice — no money moves here — and both must be decided before one does.
- **Debit notes and supplier returns — slice 4.** Goods sent back are not a void (the receipt was
  correct when it was keyed); they are their own document against the supplier's balance.
- **Purchase orders — slice 5**, and with them partial receipts and receipt-against-PO. A shop can
  receive without ever raising a PO, which is why the ordering document is last, not first.
- **Freight, duty and landed cost.** `unit_cost` is what the merchant types per unit; allocating a
  delivery charge across lines by value or weight is a second arithmetic with its own rounding
  remainder rule (the `discountToBill` shape).
- **Weighted-average or FIFO costing** — argued above; last cost is a decision, not an omission.
- **A per-line "don't update the cost" opt-out.** One more field, one more control, one more test,
  for a case the merchant can already handle by editing the product afterwards. Revisit when
  somebody asks.
- **A supplier price list / last-cost-per-supplier history.** `goods_receipt_line` already *is*
  that history; a read over it ("what did Zaki charge us last time?") is a query, not a table, and
  belongs with the PO slice that would use it.
- **`reconcile-number-sequences` for the GRN counter.** The admin repair endpoint stays
  invoice/credit-note only: those counters drift because seeded and hand-migrated rows exist ahead
  of them, and this counter is born empty in V102 with no row anywhere ahead of it.
- **Anything supplier-facing** — no portal, no login, no emailed PO, no supplier notifications. A
  supplier is a CRM record, the same way a customer is one.

---

## Tests

- `GoodsReceiptIT` (Testcontainers): a two-line receipt moves both products' stock and writes two
  `inventory_log` rows carrying **the line's** cost, not the product's · `product.cost_price` is
  raised to the line cost and a **sale placed afterwards freezes the new cost** while one placed
  before keeps the old · number allocation `GRN-YYYY-00001`, per org, per year · replay of the key
  returns the prior receipt and writes nothing · same key, different body → 409 · missing key →
  400 · untracked product → 409 with nothing written and the key unclaimed · unknown / foreign /
  inactive supplier → 404/404/409 · duplicate product line → 400 · `unit_cost` 0.00 posts stock and
  no journal line · below MANAGER → 403 on every route · a rolled-back receipt burns no number.
- `GoodsReceiptVoidIT`: the void writes one negative row per line at the same cost and the same
  `goods_receipt_id`, stock returns to its pre-receipt figure, `cost_price` is **not** restored ·
  double void → 409 · a void that would take stock below the reserved units → 409 naming them,
  nothing written · a void after a partial sale of the received units succeeds when enough remains.
- `LedgerGoodsReceiptIT` (extends the `GeneralLedgerIT` harness): after a receipt, `1200` and
  `2000` have each moved by Σ `line_total` and the trial balance is still balanced · after the
  void, both move back and the net is zero · `uncosted_stock_moves` does not grow for a received
  product · **a `?reset=true` rebuild over the pre-existing fixture produces byte-identical
  entries** (the sign-aware `RESTOCK` arm changes nothing that was already posted).
- `SupplierIT`: duplicate folded name (`أحمد` vs `احمد`) → 409 · `?q=` matches folded name and
  email and **not** phone · `name ASC` ordering under ICU with a mixed Arabic/Latin set ·
  `active=false` narrows · delete with receipts → 409, without → 204 · `phone_e164` derived and
  null when unparseable · supplier scoping across two orgs.
- `LedgerTemplatesTest` (unit, no DB): the sign-aware `RESTOCK` arm names only chart codes, and
  every arm still resolves.
- `GoodsReceiptHandlerTest` / `SupplierHandlerTest` (Mockito): role gates per verb, the 400s
  (missing key, bad window, unknown `status`, blank `q` is not an error), 405 on the wrong verb,
  404 on an unknown subresource.

---

## Definition of done

- [ ] V102 applied, codegen re-run, perfdb hand-migrated.
- [ ] `supplier` CRUD + `?q=`/`active` + the receipts subresource, gated VIEWER/STAFF/MANAGER.
- [ ] `goods_receipt` POST/list/detail/void, MANAGER throughout, key required, one transaction.
- [ ] `InventoryLogRepositoryImpl.baseInsert` takes an optional explicit `unitCost` (null keeps
      today's `product.cost_price` subselect — every existing caller unchanged) and a
      `goodsReceiptId`.
- [ ] `LedgerRepositoryImpl`'s `RESTOCK` arm is sign-aware; rebuild parity asserted.
- [ ] Movement-ledger rows carry `goods_receipt_number`, batch-loaded.
- [ ] Tests above green; `service` and `api` modules green.
- [ ] CLAUDE.md: the two new resource lines, the inventory-log amendment, the MANAGER-plane note.
- [ ] `sys-analysis/inbound/README.md` (**local-only — `sys-analysis/` is gitignored, so this
      edit does not travel with the PR**): status changed from "deferred, not in v1" to slice 1
      delivered, with the mapping table's GoodsReceipt row marked built and the remaining four
      slices named — the folder stops being a placeholder.
