# Slice: The general ledger — derived, posted, balanced by the database

> [`sys-analysis/system/accounting-future.md`](../sys-analysis/system/accounting-future.md) deferred
> a GL in v1 and named the trigger that would end the deferral: *"an org wants in-app financial
> statements (P&L, balance sheet, trial balance)"*. That trigger fired 2026-09-15. This slice builds
> the "rough shape" that document sketched — a chart of accounts, journal entries, balanced lines —
> **without touching a single money write site**, by deriving every entry from rows the domain
> already writes. **Branch `196_feat/general-ledger` off `master`, migration V101.** Frontend pair:
> `frontst/stories/162_st_general_ledger.md`.
>
> **Built 2026-09-15:** V101 + `LedgerChart` and the `ledger` domain records + `LedgerRepository`
> (the delta-first poster, `ledger_skip`, the reads, `health()`) + `LedgerService` (catch-up on
> read, rebuild, sweep) + `LedgerHandler` under `/ledger` + `LedgerPosterJob` + the
> `inventory_log.unit_cost` stamp in `InventoryLogRepositoryImpl`. Green: `LedgerTemplatesTest` 5,
> `LedgerServiceTest` 6, `LedgerHandlerAuthTest` 8, `AppConfigJobCronsTest` 2, `GeneralLedgerIT` 10,
> and the money regression set (`CashShiftIT` 7 · `InStoreSaleIT` 12 · `CounterReturnIT` 17 ·
> `SplitTenderIT` 10 · `SaleCostSnapshotIT` 3 · `CreditNoteRefundIT` 22 · `ReportReadsIT` 15).
> The perfdb bench (§Measurement) ran before merge and changed the design three times.

---

## Delivery check — ability, availability, reliability

Asked before a line was written: *can this be delivered, is what it needs available, and will it be
reliable?* The answers, from the code and the seeded benchmark database:

**Ability.** Every business event a ledger needs is already a durable row with a business timestamp
and a single writer: an issued invoice (`sales_invoice.issued_at`, `InvoiceService.issueForFulfillment`
is the one site), a verified receipt (`payment_transaction`, VERIFIED/CREDIT), an allocation
(`payment_allocation`), a credit note (`credit_note.issued_at`, `CreditNoteService.issueInTx`), an
executed refund (`refund.executed_at`, two sites both minting the same DEBIT row), a stock move
(`inventory_log`, one repository insert behind every reason), a drawer movement (`cash_movement`)
and a closed shift (`cash_shift.closed_at`). The money identities the postings rely on are enforced
by the domain classes, not assumed: `grand = subtotal + tax + shipping − discount`
(`SalesInvoice.createDraft`), `total = subtotal + tax − discount` (`CreditNote`, V89), `paid_amount
≤ grand_total`, `refunded ≤ paid`. A GL can be derived from this data — the deferral document said
so, and it was right.

**Availability.** Three gaps in the *data*, none in the schema:

- **Cost is optional and, so far, absent.** `product.cost_price` / `sales_order_line.unit_cost` (V92)
  are nullable by design; on `perfdb` **0 of 146 164 products and 0 of 1 700 159 order lines are
  costed**, and the dev DB has none either. The COGS / inventory leg of the ledger is therefore
  *empty until the merchant costs the catalogue* — so the ledger must say how much it is not
  posting (a coverage number, the `/reports/profit` convention) rather than price uncosted stock at
  zero.
- **Historical stock moves carry no cost.** `inventory_log` never had a unit cost; only order-linked
  moves can be costed from the order line. V101 adds the stamp for new rows; the old ones stay
  uncosted and counted.
- **`perfdb` sits at V96** (`tools/seed/README.md` §schema state) and has no cash shifts; V97–V101
  are hand-applied by the standing procedure before benching, and the drawer leg is measured on
  dev-scale rows only.

Everything else is available: the Docker/colima harness for the ITs, the jOOQ codegen against the
dev DB (V101 applied 2026-09-15), the seeded benchmark for the read-path measurements.

**Reliability.** The risks a GL adds, and what this slice does about each:

| Risk | Answer |
|---|---|
| A posting site is forgotten and the books silently drift | **No posting sites.** Entries are *derived* from the source rows by one idempotent set-based poster; a source row with no entry is a number on `/health` (`unposted`), never silence. |
| An entry is unbalanced | A **deferred constraint trigger** (V101) refuses the commit. The poster builds from balanced templates; the trigger is the backstop no code path can skip. `LedgerTemplatesTest` pins every template's legs and codes against the chart without a database. |
| The ledger disagrees with the rest of the app | `/health` **cross-checks** the GL receivable and customer-deposit balances against the same figures computed from the domain's own cached fields (`sales_invoice.paid_amount`, `payment.unallocated_amount`) — two independent derivations of one number, IT-pinned across every flow. |
| A replay, a crash mid-run, a concurrent read | The entry's `(org, source_type, source_id, event)` UNIQUE key makes catch-up, replay and rebuild the same statement; a per-org advisory lock serialises posters; `ON CONFLICT DO NOTHING` makes a race harmless. |
| A mapping defect ships | `POST /ledger/rebuild?reset=true` re-derives the whole journal from the sources — the ledger is a cache of the truth, never the truth. |
| The source data is wrong (an invoice whose totals do not add up) | Each template has a *guard*; a row that fails it is **counted as unposted**, not posted wrong. |
| Cost of the catch-up on read | Measured on `perfdb` before merge (§Measurement) — and the measurement changed the design three times: the anti-join is taken over the base predicate alone (a hash anti join on the entry key, not a 6-million-row nested-loop filter); rows the poster judges unpostable are recorded in `ledger_skip` so they leave the delta instead of being re-costed on every read; and `journal_line` carries `posted_at` so the trial balance is one index range per account, never a join back to the entry. Plus the one index the sources were missing (`payment_allocation (org_id)`). |

Verdict: deliverable now, with the COGS leg honest about its coverage. Built as below.

---

## Goal

A per-org general ledger a manager can open and trust: a trial balance for any window, an income
statement and a balance sheet derived from it, a journal of every posting with its legs, an
account statement with a running balance, and a health read that says in numbers whether the
books agree with the rest of the app.

Done means: every money and stock event the shop records appears as a balanced double-entry
posting dated at the event's business instant; the sum of debits equals the sum of credits over
any window; the receivable and deposit balances agree with the app's own cached figures to the
piastre; a read is never stale (it catches the ledger up first); a rebuild reproduces the journal
from the sources; and none of it changes how a sale, a refund or a restock is written.

---

## What exists, what is missing

- Every event is a row (see the delivery check). `ReportRepositoryImpl` already aggregates three
  of them (`revenue`: invoiced / collected / refunded), `CashShiftService.expectedCash` derives the
  drawer from stamped rows, `/reports/profit` computes COGS over costed lines.
- Missing: the *ledger view* — "what is the balance of Cash right now?", "what is the P&L for
  September?" — and any notion of a balanced posting, an account, or a journal.

---

## Why this shape

### Derived, then posted — not posted at the write sites

The deferral document's fear was that double-entry is *invasive*: every business event has to emit
a balanced entry, forever, in every future feature. That fear is right about the usual design and
the reason this slice does not use it. Here the money write sites are untouched. One poster
(`LedgerRepositoryImpl.postMissing`) runs a set-based `INSERT … SELECT` per event kind: select the
source rows that imply an entry, skip the ones already posted (`NOT EXISTS` on the entry's unique
source key), number and insert the rest, write their legs from a `VALUES` template joined to the
org's chart. A future feature that writes a new kind of money row adds a **template**, not a hook —
and until it does, `/health` counts that kind as absent rather than the books lying.

### Catch-up on read, sweep in the background, rebuild on demand

Three callers, one statement. Every `/ledger/*` read runs the poster for the org first (a few
anti-joins over the org's rows, in its own short transaction), so a manager never sees a journal
older than their own request. The `ledger-poster` JobRunr job (every five minutes) keeps unread
orgs current and is what backfills history on a fresh deployment. `POST /ledger/rebuild` is the
same poster under its explicit name; `?reset=true` deletes the org's journal first (derived data —
a cache flush that also re-numbers).

### The database refuses an unbalanced entry

`journal_line_balanced` is a **deferred constraint trigger**: at COMMIT, every entry touched must
have `Σ DR = Σ CR` over at least two legs, or the transaction rolls back. The poster's templates
are balanced by construction (`LedgerTemplatesTest`); the trigger is for the path nobody wrote.

### Business time, not poster time

`journal_entry.posted_at` is the source event's instant (`issued_at`, `occurred_at`, `executed_at`,
`created_at`, `recorded_at`, `closed_at`) — so a backfill dates history correctly and a window read
is the same whether the entry was posted live or a week later.

### The delta, then the work — and a memory of what could not be posted

Every read runs the poster, so the poster's steady state is "nothing to do" and that has to be
cheap. Each template therefore finds its delta first — `SELECT s.id … WHERE base AND NOT EXISTS
(entry) AND NOT EXISTS (skip)`, a hash anti join on the entry's unique key, materialised — and only
then joins, costs and guards those rows. A delta row the guard refuses (a stock move with no cost,
an invoice whose totals do not add up, a receipt on a rail the chart does not know) is written to
**`ledger_skip`** under the entry's own key shape with a reason (`UNCOSTED`, `TOTALS_MISMATCH`,
`UNKNOWN_RAIL`), so it leaves the delta for good: without that, perfdb's largest org re-costed its
24 064 uncosted stock rows on every read (342 ms of a 538 ms catch-up). `/health` counts skips per
reason as coverage; `unposted` is then exactly what it should be — rows that should have posted
and did not, i.e. drift. A reset rebuild clears the skips, which is how a newly known rail or a
repaired row gets judged again.

### Cost: the order line for sales, a new stamp for everything else

COGS on a sale is `quantity × sales_order_line.unit_cost` — the same frozen snapshot
`/reports/profit` uses, so the two cannot disagree. A restock, an adjustment or a stocktake has no
order line; V101 adds `inventory_log.unit_cost`, stamped from `product.cost_price` inside the log
insert (one repository method, no caller changes) — V92's rule one table over. A move with no cost
is **skipped and counted** (`uncosted_stock_moves`), never priced at zero.

### Void is a reversal, not an edit

An inert invoice voided for reissue keeps its issue entry; the void posts a mirror entry dated the
void; the replacement posts anew. The journal stays a history. The same for a voided credit note
(`credit_note` has no `voided_at`; `updated_at` is the void's instant, since the status flips once).

### Receipts are keyed on the transaction

`RECEIPT/RECEIVED` derives from `payment_transaction` (VERIFIED, CREDIT), not from `payment`: an
ORPHAN receipt is real money at the bank from the day it was verified, and belongs in customer
deposits until it is matched or refunded. The deposit cross-check adds those orphans to
`Σ payment.unallocated_amount` for the same reason.

---

## The chart (`LedgerChart`, one list)

| Code | Account | Type | Normal |
|---|---|---|---|
| 1000 | Cash on hand | ASSET | DR |
| 1010 | Bank & InstaPay | ASSET | DR |
| 1020 | Card processor receivable | ASSET | DR |
| 1100 | Accounts receivable | ASSET | DR |
| 1200 | Inventory | ASSET | DR |
| 2000 | Supplier purchases (unbilled) | LIABILITY | CR |
| 2100 | Customer deposits | LIABILITY | CR |
| 2200 | VAT payable | LIABILITY | CR |
| 3000 | Owner contributions | EQUITY | CR |
| 3100 | Owner drawings & till pay-outs | EQUITY | DR |
| 4000 | Sales revenue | REVENUE | CR |
| 4050 | Sales discounts | REVENUE | DR |
| 4100 | Shipping revenue | REVENUE | CR |
| 4200 | Sales returns | REVENUE | DR |
| 5000 | Cost of goods sold | EXPENSE | DR |
| 5100 | Inventory shrinkage & adjustments | EXPENSE | DR |
| 5200 | Cash over / short | EXPENSE | DR |

Deliberately small: every account is one a template posts to (pinned). Rows are materialised per
org, lazily, the first time the org's ledger is touched (`INSERT … ON CONFLICT DO NOTHING`); the
frontend localises names by code, the stored name is the English fallback. The rail → asset map
(`LedgerChart.cashAccountFor`) is mirrored by one SQL `CASE` and the unit test pins the pair, so a
new provider cannot land in Java without landing in SQL.

---

## The postings (`LedgerRepositoryImpl.TEMPLATES`)

| Source / event | Dated | Legs (zero-amount legs are simply absent) |
|---|---|---|
| INVOICE / ISSUED | `issued_at` | DR 1100 grand · CR 4000 subtotal · DR 4050 discount · CR 4100 shipping · CR 2200 tax |
| INVOICE / VOIDED | `voided_at` | the mirror of ISSUED |
| RECEIPT / RECEIVED (VERIFIED CREDIT `payment_transaction`) | `occurred_at` | DR cash-by-rail · CR 2100 |
| ALLOCATION / APPLIED | `created_at` | DR 2100 · CR 1100 |
| CREDIT_NOTE / ISSUED | `issued_at` | DR 4200 subtotal · DR 2200 tax · CR 4050 discount · CR 1100 total |
| CREDIT_NOTE / VOIDED | `updated_at` | the mirror of ISSUED |
| REFUND / EXECUTED | `executed_at` | DR 1100 (credit-note-backed) or DR 2100 (direct: overpayment, counter change) · CR cash-by-rail |
| STOCK / MOVED (`inventory_log`, `stock_delta ≠ 0`) | `created_at` | SOLD: DR 5000 / CR 1200 · RETURNED, RESTOCKED_FAILED_FULFILLMENT: DR 1200 / CR 5000 · RESTOCK: DR 1200 / CR 2000 · ADJUSTMENT, STOCKTAKE: down → DR 5100 / CR 1200, up → DR 1200 / CR 5100 — all at `|delta| × cost`, order-linked moves costed from the order line, others from the V101 stamp; uncosted → skipped, counted |
| CASH_MOVEMENT / RECORDED | `recorded_at` | PAY_IN: DR 1000 / CR 3000 · PAY_OUT: DR 3100 / CR 1000 |
| CASH_SHIFT / CLOSED (`counted ≠ expected`) | `closed_at` | over: DR 1000 / CR 5200 · short: DR 5200 / CR 1000 |

Guards (a row failing one is unposted and counted, never posted wrong): the invoice and credit-note
total identities; a receipt's or refund's provider on the known list; a stock move's cost present
and positive. RESERVED / RELEASED hold and free units without moving value and are not events.

Worked example — a cash sale of 3 × 10.00 with 50.00 tendered, cost 4.00, stock initialised at 10:
RESTOCK (DR 1200 40 / CR 2000 40) · INVOICE (DR 1100 30 / CR 4000 30) · RECEIPT (DR 1000 50 / CR
2100 50) · ALLOCATION (DR 2100 30 / CR 1100 30) · change REFUND (DR 2100 20 / CR 1000 20) · SOLD
(DR 5000 12 / CR 1200 12). Cash 30, receivable 0, deposits 0, revenue 30, COGS 12, inventory 28,
unbilled purchases 40 — assets 58 = claims 58.

---

## API — `/api/orgs/{orgId}/ledger` (`LedgerHandler`)

**MANAGER for every read** — the ledger carries cost of goods, margins and the owner's drawings, the
`/reports/profit` plane, gated whole rather than per field. **OWNER for the rebuild.** GET only on
the reads (405 otherwise), POST only on the rebuild, unknown route 404. Every read catches the org's
ledger up first.

- `GET /trial-balance?from=&to=` → `{from, to, accounts:[{code, name, type, normal_side, opening,
  debit, credit, closing}], total_debit, total_credit}`. Every chart account, zero-filled.
  `opening`/`closing` are signed on the account's **normal side** (`closing = opening + (debit −
  credit)` for DR-normal, `+ (credit − debit)` for CR-normal); `debit`/`credit` are the window's raw
  movement sums, so `total_debit == total_credit` is the visible proof. The client derives the
  income statement (REVENUE / EXPENSE movements) and the balance sheet (ASSET / LIABILITY / EQUITY
  closings plus the period's net) from this one read.
- `GET /journal?from=&to=&account=&page=&size=` → `{…, total, items:[{id, entry_no, posted_at,
  source_type, source_id, event, memo, lines:[{seq, account_code, account_name, side, amount}]}]}`,
  oldest first (`posted_at, entry_no`); `account` narrows to entries with a leg on that code.
- `GET /accounts/{code}/lines?from=&to=&page=&size=` → `{account, from, to, opening, page, size,
  total, items:[{entry_id, entry_no, posted_at, source_type, source_id, event, memo, side, amount,
  balance_after}], closing}` — the running balance is a window function over the **whole** window
  in posting order, then paged, so page 2 continues page 1. Unknown code → 404.
- `GET /health` → `{ok, posted_entries, unbalanced_entries, unposted:{"INVOICE/ISSUED":0, …},
  skipped:{"STOCK/MOVED:UNCOSTED": n, …}, uncosted_stock_moves, checks:[{name, ledger, source,
  ok}], last_posted_at}`. `ok` iff nothing is unbalanced, nothing is unposted and every check
  agrees; `skipped` (and its named member `uncosted_stock_moves`) is coverage, not a fault. Checks: `accounts_receivable` = Σ live invoices `(grand − paid)` − Σ live notes `total` +
  Σ executed note-backed refunds; `customer_deposits` = Σ `payment.unallocated_amount` + Σ verified
  orphan receipts with no payment row.
- `POST /rebuild[?reset=true]` → `{reset, removed, posted, posted_by_kind}`.

Window rules are `ReportService`'s (default 30 days ending now, `from < to`, ≤ 366 days, ISO-8601);
page 0-based, size 1–200 (default 50).

### Errors

| Case | Status |
|---|---|
| anonymous | 401 |
| non-member, VIEWER, STAFF on a read; MANAGER on the rebuild | 403 |
| mutating verb on a read route, GET on the rebuild | 405 |
| unknown route, unknown account code on the statement | 404 |
| bad window / page / size / `account` code | 400 |

---

## Known limits (stated, not hidden)

- **Pay-outs are the owner's drawings.** `cash_movement.reason` is free text; a supplier paid from
  the till and a bank drop post the same way until a reason taxonomy exists. P&L-safe (equity, not
  expense); the balance sheet shows cash left to the owner.
- **Card settlement is not modelled.** A Paymob receipt sits in 1020 until a future slice records
  the processor's payout and fees (there is no fee field today).
- **No period close, no retained earnings** — the balance sheet plugs the cumulative net.
- **Procurement is unbilled**: a restock credits 2000 at the product's current cost; supplier bills
  (inbound context, deferred) will clear it.
- Reads run the poster under a read-only SUPPORT impersonation too — idempotent materialisation of
  derived data, not a business write.

---

## Migration V101

`ledger_account` (per-org chart, `UNIQUE (org_id, code)`), `journal_entry` (`UNIQUE (org_id,
source_type, source_id, event)` — the idempotency key; `UNIQUE (org_id, entry_no)`; index
`(org_id, posted_at, entry_no)`), `journal_line` (`amount > 0`, `side IN ('DR','CR')`, **`posted_at`
copied from the entry** so the per-account reads are one range of `(org_id, account_id,
posted_at)`), `ledger_skip` (the poster's memory of unpostable rows, PK on the entry key shape), the
`journal_entry_must_balance()` deferred constraint trigger, **`payment_allocation (org_id)`** (the one
source table with no per-org index — its check was a parallel seq scan over 696 569 rows for any
org), and `inventory_log.unit_cost NUMERIC(14,2)` nullable, no backfill.

---

## Tests

- `LedgerTemplatesTest` (repository, no DB): every code a template names is on the chart; every
  chart account is named by a template; every template has a DR and a CR leg; the SQL rail map
  agrees with `LedgerChart.cashAccountFor` for every `PaymentProvider`; keys unique.
- `LedgerServiceTest` (service, no DB): window / page / account rules, the health verdict, the
  sweep continuing past a failing org.
- `LedgerHandlerAuthTest` (api, no DB): 401 / 403 / 200 per role on every read, OWNER-only
  rebuild with `reset`, 405 / 404 / 400 routing, the statement code from the path.
- `GeneralLedgerIT` (api, Postgres): the counter sale with change (five entries, assets = claims,
  both checks agree, the V101 stamp), the counter return with restock, the online prepayment →
  delivery → credit note → refund chain with the receivable check true at every step, void +
  reissue as reversal + new entry, drawer movements and a short close, stocktake shrinkage and
  gain, idempotent catch-up and reset-rebuild renumbering from 1, reads that are current without
  a poster call, org isolation, the statement's running balance across pages and the journal's
  account filter, the uncosted move landing in `ledger_skip` once (never counted as drift, gone
  after a reset rebuild), and the database refusing a hand-written lone leg.
- `AppConfigJobCronsTest`: the `ledger-poster` job is registered with its default cron.

---

## Measurement (`tools/seed/results/general_ledger_196.txt`)

On `perfdb`'s largest org (3 562 invoices, 4 266 receipts, 3 562 allocations, 24 184 stock rows —
all uncosted), through the production `LedgerService` (`PerfdbLedgerBench`, `PERFDB_BENCH=1`) and
`EXPLAIN (ANALYZE, BUFFERS)` warm, twice, second kept. **The first bench changed the design three
times** before merge:

| What | As first written | After |
|---|---|---|
| No-op catch-up (every read pays it) | 2 967 ms | **100 ms** |
| INVOICE/ISSUED anti-join | 1 008 ms — guard in the predicate → planner estimated 17 of 3 562 rows → nested-loop filter over 6.3 M pairs | 2.6 ms — base predicate only, hash anti join on the entry key |
| STOCK/MOVED | 463 ms → 342 ms — the 24 064 uncosted rows re-costed on every read | 10 ms — `ledger_skip` takes them out of the delta |
| ALLOCATION/APPLIED | 61 ms warm / 124 ms cold — `payment_allocation` had no per-org index: a parallel seq scan over 696 569 rows for **any** org | 12 ms — V101's `payment_allocation (org_id)` |
| Trial balance 366d | 838 ms cold — every line joined to its entry for the date | ~160 ms incl. its catch-up — `journal_line.posted_at` + `(org, account, posted_at)` |
| Full backfill of the org (11 570 entries) | — | 3.3 s |
| Trial balance 30d / journal page / statement page / health, each incl. its catch-up | — | 193 / ~115 / ~118 / ~212 ms |

A catch-up over 500 ms logs a WARN with the per-kind split (`LedgerRepositoryImpl.SLOW_CATCH_UP_MS`),
so a plan regression is a log line, not a slow page. The whole-perfdb backfill — the job's first
tick on a deployment of that size: 201 orgs, 1.66 M entries — took 9.6 min org by org (a lower
bound: part of it had been posted by an interrupted earlier run), and the following no-op tick
over all 201 orgs 47 s; the journal is 2,272,403 entries / 5,251,767 lines / 3340 MB with
4,973,865 skips for the 5 M uncosted stock rows. Storage at that scale is real — journal_entry 1135 MB,
journal_line 1323 MB, ledger_skip 882 MB with indexes, more than the sources they derive from — and it is
the price of "judged once": a shop of ordinary size is a hundredth of it. Details in the results file.
