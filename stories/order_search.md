# Slice: Order search (`?q=` on the orders worklist)

> The admin Orders page is a title and five status tabs. A merchant with a number in hand from a
> WhatsApp thread, or a customer on the phone who says only their name, has no way in short of
> scrolling. The worklist read
> ([`SalesOrderHandler.doGetOrList`](../api/src/main/java/com/loai/inventory/api/servlet/handler/SalesOrderHandler.java))
> takes `?status=` and `?channel=`, plus `?order_number=` — an **exact, case-sensitive** lookup
> that returns one order, not a filtered list (`lookup_order_by_number.md`: numbers arrive by
> copy-paste from transfer notes). Customers, inventory and products all have a free-text `?q=`;
> orders do not. This slice adds it, on the same predicate discipline the customer directory set
> (`CustomerRepositoryImpl.searchCondition`: fold on both sides, one condition for rows and total).
> Frontend pair: `frontst` story 142 (branch `140_feat/orders-search`, the design canvas
> `design/orders-search-filters.html`). Branch `179_feat/order-search`, **no migration**.

---

## Today (the gap)

- **No free-text read on `/sales-orders`.** `SalesOrderService.list(orgId, status, channel, page,
  size)` narrows by two enums only. `?order_number=` is a different door: exact match, single
  object, 404 otherwise — the right shape for a pasted reference, the wrong one for "the order for
  Ahmed" or "the one ending in 42".
- The data to search is already there and already normalised: `sales_order.order_number`
  (`SO-2026-00042`); the CRM row's `customer.name_search` (V62's generated fold) and
  `customer.phone_e164` (V79); and, since V87, the walk-in contact frozen on the order —
  `sales_order.customer_name` / `customer_phone` (`Text.normalizeNumeric`-folded, not E.164,
  only ever set when `customer_id IS NULL`).

## Goal

**One field on the worklist answers "which order?" from whatever the merchant has** — a fragment
of the number, the customer's name in any Arabic spelling, or the digits of their phone —
composing with the status tab and the channel filter, with a `total` that equals the rows.

## Design

### Contract

`GET /api/orgs/{orgId}/sales-orders?status=&channel=&q=&page=&size=` (VIEWER). `q` is trimmed;
blank or whitespace-only is **absent** (the unfiltered read — the customer directory's rule), not
a search for spaces. No minimum length (the measurement behind that is on
`CustomerRepositoryImpl.searchCondition`: the predicate is org-scoped, the planner never chooses
a trigram index, cost is linear in the tenant's own ledger). Response shape unchanged:
`PageResponse<SalesOrderResponse>`; ordering unchanged (status → queue oldest-first, else ledger
newest-first). `?order_number=` keeps its door and its precedence.

### The predicate (`SalesOrderRepositoryImpl.listConditions(orgId, status, channel, q)`)

One definition for `list` and `count`. With `q`, OR of:

- **number** — `order_number ILIKE '%q%'`: `"42"` and `"so-2026-00042"` both find
  `SO-2026-00042`.
- **name** — folded on **both** sides with the DB's own `fold_search`: the CRM row through
  `EXISTS (customer.id = sales_order.customer_id AND name_search LIKE '%fold(q)%')`, and the
  walk-in `fold_search(customer_name) LIKE '%fold(q)%'` (no generated twin exists for it — it is
  folded in the query, org-scoped, fine). So `"احمد"` finds `"أحمد محمود"`.
- **phone** — only when `q` carries digits (after `Text.normalizeNumeric`, so Arabic-Indic digits
  fold): the CRM `phone_e164 LIKE '%digits%'` and the walk-in
  `regexp_replace(customer_phone, '[^0-9]', '', 'g') LIKE '%digits%'`. `"0100 123 4567"`,
  `"1001234567"` and `"+201001234567"` all find the same order.

`status` and `channel` AND onto the OR-group exactly as before. The `EXISTS` legs are one PK hit
per candidate row.

### Why not a second endpoint

A search that returns a *list* is the worklist with one more predicate — same page shape, same
ordering, same tab. Splitting it would give the frontend two code paths for one field. The exact
`?order_number=` door stays because its contract (one object, 404) is what the transfer-note
flow relies on.

## Errors

- Unknown `status` / `channel` → 400 (unchanged). `q` cannot fail: any string is a valid search;
  no match is `data: [], total: 0`.

## Tests

- `OrderSearchIT` (service against the real jOOQ repository, Testcontainers Postgres 17):
  number contains + case-insensitive + rows == total; CRM name folded both sides (`احمد` ↔ `أحمد`,
  Latin casefold); phone digits — CRM E.164 and walk-in as typed, spaces / leading 0 / `+20`, and
  Arabic-Indic digits; walk-in name without a CRM row; `q` composing with `status` and `channel`,
  blank = absent, no match = 0 rows and `total: 0`; a second org's orders never match.
- `OrderLookupHandlerAuthTest.bareGet_returnsTheWorklist_serviceListCalled` — the handler passes
  `q` through the six-arg `list`.

## Out of scope / known gaps

- **No date range, no balance filter.** `?from/?to` and `?balance=` are the next two parameters
  the frontend's Filters sheet waits for; each is its own slice.
- **No relevance ranking.** Results keep the worklist order; a hit on the number is not ranked
  above a hit on a phone. Fine at tenant scale; revisit if a merchant asks.
- **No index for the walk-in legs.** `fold_search(customer_name)` and the `regexp_replace` on
  `customer_phone` are computed per candidate row under the org index. Measured reasoning as for
  the customer directory; add a generated `customer_name_search` column if a tenant's ledger
  ever makes it show.
