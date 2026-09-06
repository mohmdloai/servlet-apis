# Slice: Transaction ledger filters (`?q=`, `?from/?to`, `?min/?max`, `?sort=` + a money summary and the matched order on `GET /payment-transactions`)

> The admin Money page's reconciliation ledger ("All transfers" / "Orphan queue") is a bare
> chronological page. The list read
> ([`PaymentTransactionHandler.doList`](../api/src/main/java/com/loai/inventory/api/servlet/handler/PaymentTransactionHandler.java))
> takes the two status predicates, `has_payment`, `provider`, an **exact** `provider_ref` and
> `sales_order_id` — and the frontend sends the tab's state and a page. "Did this transfer arrive?"
> — the first support question — can only be asked with the exact reference, inside the record and
> verify flows; "what came in on Tuesday by transfer, and how much?" cannot be asked at all; a
> customer who cannot give the reference but knows they sent 1,240 has no door. Orders got this in
> `order_filters.md`, Invoices in `invoice_filters.md`, the stock overview in
> `inventory_filters.md`; this slice gives the ledger the same vocabulary, plus the one thing a
> ledger row was missing: which order the money went to. Design canvas
> `design/transactions-search-filters.html` (frontst); frontend pair: `frontst` story 147. Branch
> `184_feat/transaction-filters`, **no migration**.

---

## Today (the gap)

- **Six predicates, one order.** `ListFilter(verificationStatus, reconciliationStatus, hasPayment,
  provider, providerRef, claimedSalesOrderId)`; the rows and a separate `count`; the ORDER BY is
  decided by "is any predicate set" — any filter at all flips the ledger to oldest-first.
- **`provider_ref` is exact**, case-sensitive, after trimming — the right rule for the support
  lookup it was built for, and useless for the four digits a customer reads out.
- **A ledger row does not name its order.** The list passes `null` for payment and order (the
  disposition is the detail's), so a search by order number or customer would find rows the operator
  cannot recognise.
- The data is on the row or one hop away: `occurred_at` (NOT NULL — the bank's time, what the row
  shows), `amount`, `direction`, `verification_status`; the matched order through the 1:1
  `payment.sales_order_id`; the claimed order through `claimed_sales_order_id`; the customer through
  `payment.customer_id`, the order's `customer_id` / walk-in `customer_name` + `customer_phone`
  (V87), or `claimed_by_customer_id`.

## Goal

**One read answers "which transfers, in what order, and how much?"** — a fragment search across
reference, order and customer, a window on the bank's time, a method, an amount band and an
explicit sort composing with the tab's own predicates, with a `total` that equals the rows, a
`summary` that adds the same rows up, and rows that name the order their money went to.

## Design

### Contract

`GET /api/orgs/{orgId}/payment-transactions?verification_status=&reconciliation_status=&has_payment=&provider=&provider_ref=&sales_order_id=&q=&from=&to=&min=&max=&sort=&page=&size=`
(VIEWER). Every new parameter is optional; blank is absent; all AND.

| Parameter | Meaning | Malformed |
| --- | --- | --- |
| `q` | Free text, trimmed. Four legs, ORed: `provider_ref ILIKE '%q%'`; the **linked order's** number by fragment — the 1:1 payment's `sales_order_id` (MATCHED / UNDERPAID / OVERPAID, a resolved orphan) or the row's `claimed_sales_order_id` (a claim); that order's customer by folded name (`fold_search` both sides — CRM `name_search`, walk-in `customer_name` folded in the query) or phone digits (CRM `phone_e164`, walk-in `customer_phone` stripped; Arabic-Indic folded); the claimant (`claimed_by_customer_id`) the same way. | never — blank is absent |
| `from` / `to` | ISO-8601 date-times, half-open `[from, to)` on **`occurred_at`** — the time the row shows and the bank statement carries, not `recorded_at`. Either side may be open. | bare date → 400; `from >= to` → 400 |
| `min` / `max` | Inclusive bounds on `amount`, non-negative decimals. `min = max` finds one exact amount — the customer who knows they sent 1,240. | non-numeric / negative → 400; `min > max` → 400 |
| `sort` | `newest` (`occurred_at DESC, id DESC`) or `oldest` (`occurred_at ASC, id ASC`). Absent = the view's own order (below). | anything else → 400 naming the two |

`provider` (name or DB literal), `provider_ref` (exact — the record and verify flows' lookup
stays as it was) and the four state predicates are unchanged.

### Ordering: the queue-vs-ledger rule, narrowed to what it meant

The rule keyed on "is any predicate set". It now keys on the **state** predicates only:
`verification_status`, `reconciliation_status`, `has_payment`, `sales_order_id` make the read a
queue (oldest first — the next thing to work; the claims queue keeps its clock order). The
**narrowing** dimensions — `provider`, `provider_ref`, `q`, the window, the band — never change the
order on their own: choosing InstaPay on "All transfers" narrows the page, it does not flip it to
a queue. `sort` overrides both. The one observable change: `?provider=` or `?provider_ref=` alone
used to read oldest-first and now reads newest-first — the exact-reference lookup returns a row
or nothing, so nothing depended on it.

### Envelope

`PageResponse` **plus `summary: {money_in, money_out}`**, both always present, `0.00` when empty:

- `money_in` = Σ `amount` over **VERIFIED CREDIT** rows the predicate matches — a shopper's claim
  resting UNVERIFIED (or NOT_FOUND / ABANDONED) is a row in the ledger, not money that arrived.
- `money_out` = Σ `amount` over **VERIFIED DEBIT** rows — executed refunds.

One `stats` query (`count`, two conditional sums) on the rows' own predicate replaces the separate
`count` on the list path, so the pager's total, the rows and the money line can never disagree.

### Rows: the matched order and its customer

Every row keeps the claim context it carried (`customer`, `claimed_order`). A row whose 1:1
payment is order-linked now also carries **`order`** (`OrderSummary`: id, number, status, totals —
plus **`customer_name`**, the walk-in contact typed at the counter, null for a CRM customer) and,
when the payment or that order names a CRM customer, **`customer`** (the same slot the claim
uses; a claim's own claimant wins when both exist). Batch-loaded per page — one payments read by
transaction id (`PaymentRepository.findByTransactionIds`), one orders read, one customers read —
never per row. The **payment itself stays a detail read** (the disposition is the detail's, and a
`PaymentSummary` per row is weight no list needs).

### Data flow

1. `PaymentTransactionMapper.toListFilter` parses the whole query string into the one
   `ListFilter` (grown by `q`, `occurredFrom`, `occurredTo`, `minAmount`, `maxAmount`, `sort`;
   the six-arg constructor stays as a delegate) — `QueryParams` for date-time / money / enum, the
   cross-field rules named above.
2. `PaymentTransactionRepositoryImpl.conditions` adds the four `q` legs (org-scoped `EXISTS`
   through `sales_order` for the linked order, the `customerMatches` `EXISTS` for the CRM
   customer), the window, the band; `order(filter)` decides the ORDER BY; `stats` runs the
   count + sums.
3. `PaymentTransactionService.list` reads rows + stats, batch-loads payments → orders → customers,
   and returns `TransactionPage` with `matchedOrders` / `matchedCustomers` keyed by transaction id
   and the `ListStats`.
4. The handler answers `TransactionListResponse` (the `PageResponse` plus `summary`) with each row
   through `withContext(txn, null, matchedOrder, customer, claimedOrder, null)`.

## Tests

- `TransactionFiltersIT` (11): every `q` leg (reference fragment, case-insensitive; the order
  number through the payment and the claim; CRM name folded, walk-in name, walk-in phone digits,
  CRM phone from Arabic-Indic digits; blank = absent; unknown = empty), the half-open window on
  `occurred_at`, the inclusive band and `min = max`, the method composing with the window without
  flipping the ledger order, the explicit sort against both defaults, the summary (VERIFIED money
  in and out; a claim excluded; empty = `0.00`), the row context (matched order + CRM customer;
  walk-in name on the order; an orphan links to nothing; a claim keeps its own), and org isolation
  through every leg.
- `PaymentTransactionHandlerAuthTest` (+4): the bare GET is the unfiltered ledger with a summary on
  the envelope; the twelve parameters reach the service as one filter (trimmed `q`, parsed
  date-times, decimals, sort); the 400 table (`from` bare date, `to` prose, `sort` outside the two,
  `min` non-numeric, `max` negative, unknown `provider`); inverted window / band → 400.
- The payment-family ITs (`PaymentTransactionReadIT`, `PaymentClaimVerifyIT`,
  `PaymentClaimNotFoundIT`, `ShopperPaymentClaimIT`, `OrphanRefundIT`, `OrphanResolutionIT`,
  `OrphanResolutionEdgeIT`, `PaymentVerifyIT`, `OrderPaymentsIT`) unchanged and green — the
  six-arg `ListFilter` and the four-arg `TransactionPage` delegates keep every caller compiling.

## Out of scope (named so the next slice does not re-decide it)

- An **outcome** filter on the ledger (`reconciliation_status` already exists as a parameter; the
  Orphan queue tab covers the one with work in it) and a **direction** filter — the summary
  separates in and out; both are one chip group away if asked for.
- Sorting by amount — the band does that job.
- The same treatment for `GET /payments` (the Disputed / Unallocated tabs are the Payment entity).
- An index. The predicate is org-scoped (`idx_txn_*` partials exist for the queues); the window
  and the band scan the tenant's own ledger, linear in its size, like the orders search measured.
