# Slice: Invoice filters (`?q=`, `?from/?to`, `?paid=`, `?min/?max` + status counts on the invoices worklist)

> The admin Invoices page is a title and four status tabs. The worklist read
> ([`InvoiceHandler.doList`](../api/src/main/java/com/loai/inventory/api/servlet/handler/InvoiceHandler.java),
> `invoice_reads.md`) takes `status`, `page` and `size` — nothing else. A merchant closing August
> ("what did I invoice last month, and how much of it is still owed?"), chasing a customer by name
> or phone, or looking for the partly-paid documents has no way in short of paging the ledger.
> Orders got its `?q=` in `order_search.md`; this slice gives Invoices the same door plus the
> dimensions an invoice ledger is actually read by — the issued window, the paid state, the amount
> — and the tab counts Orders already has. Frontend pair: `frontst` story 143 (branch
> `141_feat/invoices-search-filters`; the design canvas `design/invoices-search-filters.html`,
> whose "URL contract" note is this story's contract). Branch `180_feat/invoice-filters`,
> **no migration**.

---

## Today (the gap)

- **Three parameters.** `InvoiceAdminService.list(orgId, status, page, size)` narrows by one enum.
  There is no free text, no date window, no way to isolate `paid_amount > 0` rows, no bounds.
- **No counts-only read.** The tabs (`Awaiting payment · Paid · Void · All`) are numberless for the
  reason `order_status_counts.md` fixed on Orders: filling them would take four `size=1` list calls.
- **The money question is a client sum of one page.** "How much is outstanding in this set?" can
  only be answered for the 20 rows on screen, never for the filtered whole.
- The data is there and already normalised: `invoice_number`; the order's `order_number` through
  `sales_order_id`; the frozen `customer_name` / `customer_phone` snapshot (V21) and the CRM row's
  generated `name_search` (V62) / `phone_e164` (V79) through `customer_id`; `issued_at`;
  `paid_amount` / `grand_total`.

## Goal

**One read answers "which invoices, and how much?" from whatever the merchant has** — a fragment
of a number, a name in any Arabic spelling, the digits of a phone, a month, a paid state, an
amount band — composing with the status tab, with a `total` that equals the rows and a `summary`
that adds the same rows up. Plus one cheap read for the tab numbers.

## Design

### Contract

`GET /api/orgs/{orgId}/invoices?status=&q=&from=&to=&paid=&min=&max=&page=&size=` (VIEWER).
Every new parameter is optional; blank is absent.

| param | meaning | can 400 |
| --- | --- | --- |
| `q` | invoice number (contains, case-insensitive), order number (same), the frozen customer name folded on both sides, the CRM name through `customer_id`, and — only when `q` carries digits — the snapshot phone's digits and the CRM `phone_e164`. Trimmed; whitespace-only = absent. | never |
| `from` / `to` | half-open `[from, to)` on `issued_at`, ISO-8601 date-times — the `/reports` convention (`reporting_reads.md`). Either side may be open. | not a date-time; `from >= to` |
| `paid` | `none` → `paid_amount = 0`; `partial` → `0 < paid_amount < grand_total`. The ISSUED money meter as a filter; on PAID/VOID rows it is simply a predicate that never matches (a PAID row's meter is full), so the frontend does not send it on those tabs. | any other value |
| `min` / `max` | inclusive bounds on `grand_total`, plain decimals (EGP). | not a number; negative; `min > max` |

Ordering is unchanged and **only `status` decides it**: a status = queue `created_at ASC, id ASC`;
none = ledger `created_at DESC, id DESC`. The other dimensions narrow, never reorder — a date
filter on the awaiting queue still lists the oldest debt first.

**Response**: the `PageResponse` envelope plus `summary`:

```json
{ "data": [ … ], "total": 12, "page": 0, "size": 20,
  "summary": { "outstanding": 1920.00, "issued": 15360.00 } }
```

- `outstanding` = Σ `grand_total − paid_amount` over the **ISSUED** rows of the whole filtered set.
- `issued` = Σ `grand_total` over the **ISSUED + PAID** rows — a VOID document's amount is no longer
  in force, so it never counts.
- Both always present, `0.00` for an empty set, computed in the **same query** as `total`
  (`SalesInvoiceRepository.stats`) with the **same predicate** as the rows.

`GET /api/orgs/{orgId}/invoices/status-counts` (VIEWER) —
`{ "counts": { "DRAFT": 0, "ISSUED": 12, "PAID": 41, "VOID": 3 }, "total": 56 }`. Every status
present (`0` included); `total` = the unfiltered ledger count = Σ counts. Routed as a fixed segment
before the `{id}` parse, like `/sales-orders/status-counts`; `POST` → 405.

### The predicate (`SalesInvoiceRepositoryImpl.conditions(orgId, filter)`)

One definition for `list`, `stats` and `countByStatus`, so none of the three can disagree. The
filter is one value, `InvoiceListFilter` (domain): `status, q, issuedFrom, issuedTo, paid,
minTotal, maxTotal`. With `q`, the OR-group is the `order_search.md` legs transposed:

- `invoice_number ILIKE '%q%'`; `EXISTS (sales_order.id = sales_invoice.sales_order_id AND
  order_number ILIKE '%q%')`;
- `fold_search(customer_name) LIKE '%fold_search(q)%'` — the snapshot has no generated twin, so it
  is folded in the query (org-scoped, linear in the tenant's ledger — the measurement behind the
  customer search applies); `EXISTS (customer.id = customer_id AND name_search LIKE …)`;
- with digits: `regexp_replace(customer_phone, '[^0-9]', '', 'g') LIKE '%digits%'` and
  `EXISTS (… phone_e164 LIKE '%digits%')`.

`issued_at >= from`, `issued_at < to`, the paid predicate and the `grand_total` bounds AND on.
The window reads `issued_at`, not `created_at`: it is what the document says and what the row
shows ("Issued 12 Aug"); the two differ only by the issuance transaction's clock.

### Why the summary rides the list

The worklist shows "2 invoices match · EGP 1,920.00 outstanding" under its applied filters. That
figure must be the **whole** set's, not the page's, and it must agree with `total` — so it comes
from the one stats query the pager already needs (`COUNT(*)` + two conditional `SUM`s), not from
a second endpoint that could read a different instant.

### Indexes

No new index. `sales_invoice_org_status_idx (org_id, status, created_at, id)` (V66) still drives
the queue; the `q`/date/paid/amount legs are org-scoped filters over the tenant's own ledger. A
dedicated `(org_id, issued_at)` for the ledger-wide date window is deferred until an EXPLAIN on
the perf seed says it is needed (`docs/query-index-audit.md` discipline).

## Errors

- Unknown `status` → 400 (unchanged). `paid` outside `none|partial` → 400.
- `from`/`to` not ISO-8601 date-times (a bare `2026-08-01` is refused, as on `/reports`) → 400;
  `from >= to` → 400.
- `min`/`max` not numbers, negative, or `min > max` → 400.
- `q` cannot fail; no match is `data: [], total: 0, summary: {0.00, 0.00}`.

## Tests

- **`InvoiceFiltersIT`** (service against the real jOOQ repositories, Testcontainers Postgres 17):
  `q` by invoice and order number (contains, case-insensitive, rows == total); the frozen snapshot
  name folded both ways (`منى` ↔ `منى عادل`) and the CRM name (`احمد` ↔ `أحمد محمود`); phone digits
  on the snapshot and the CRM E.164 (spaces, `+20`, Arabic-Indic digits); blank `q` is absent and
  `q` composes with `status`; the window is half-open on `issued_at` with either side open, and
  narrows without reordering (queue ASC / ledger DESC preserved); `paid=none` / `partial` against
  an untouched, a partly-paid and a PAID row; inclusive amount bounds; the summary adds up the
  filtered set (outstanding = ISSUED remainder only, issued excludes VOID, `0.00` scale 2 when
  empty); status counts carry every status, equal the ledger total, and are org-scoped.
- **`InvoiceHandlerAuthTest`**: the seven parameters reach the service as one trimmed/parsed
  `InvoiceListFilter`; bare date, prose date, `paid=half`, `min=abc`, `max=-1`, `from > to`,
  `min > max` are 400s with the service never called; `GET /status-counts` is allowed for VIEWER
  and never touches `list`; `POST /status-counts` → 405.
- **`InvoiceReadsIT`** unchanged in substance (calls go through `InvoiceListFilter.ofStatus`).

## Out of scope

- A `?customer_id=` door (the customer page already lists its invoices through
  `findByCustomerId`).
- A due date: `sales_invoice` has none (`invoice_reads.md`), so there is no "overdue" filter to add.
