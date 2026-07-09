# Slice: Product barcode lookup + write-through (scanner resolve seam)

> The backend half of the camera-scanner vision (frontend `16_st_barcode_scanner_sale.md`).
>
> **This is not new scope — it's the deferred step 1 of the canonical in-store flow.**
> [`FLOW.md §3`](../sys-analysis/outbound/FLOW.md) opens Flow 1 (in-store) with, verbatim:
> `1. Cashier scans barcode → product lookup via (org_id, barcode)`. The `in_store_sale.md`
> slice shipped everything *after* that step but explicitly punted the lookup itself ("No barcode
> lookup — lines are by `product_id`… a thin add-on for a later slice"). This slice is that add-on.
>
> The `product.barcode` column already exists — `V16__Add_product_barcode.sql` added it
> (per-org unique, nullable, comment: *"for in-store scanner lookup"*) — but **nothing reads or
> writes it today**: no service field, no handler param, no repository method. This slice makes the
> dormant column usable so a scanned barcode can resolve to a `product_id`, which every existing
> write path (in-store sale, restock) already speaks. **No existing endpoint changes shape** — the
> scanner resolves barcode → `product_id` on the client, then calls the unchanged POSTs
> (`FLOW.md §5`: the two channels "share *everything* about the underlying schema and money model").

---

## Goal

1. **Resolve** — `GET /api/orgs/{orgId}/products?barcode=<code>` returns the single product whose
   `(org_id, barcode)` matches, or **404**. This is the scanner's resolve call, shared by both the
   stocking and selling flows.
2. **Write-through** — `POST`/`PUT /api/orgs/{orgId}/products` accept and persist `barcode` (today
   it's silently dropped). A staff member can assign a barcode to a product — including *by scanning*
   it in the product form.
3. **Idempotent restock** — `POST /api/orgs/{orgId}/inventory/{productId}/restock` accepts an
   optional client-supplied `Idempotency-Key`, deduped per org, so a retried/offline-replayed
   `+stock` applies **exactly once**. Designed now so the deferred offline stock-take queue drops in
   later with no schema change (see `16_st_…` §Out — offline).

Done means: a POST product with `barcode` round-trips; `GET ?barcode=` finds it or 404s; and a
double-submitted restock with the same key increments stock once.

---

## Why the lookup returns identity + price but **not** stock

The resolve response carries what a client needs to render a line **immediately** without a second
call — product identity **and price** (`base_price`, stable). It deliberately **omits stock**:

- **Stock is stale on arrival.** The in-store sale checks availability under `SELECT … FOR UPDATE`
  *inside the checkout txn* and 409s on shortage (`in_store_sale.md` §Concurrency) — that is the only
  stock truth that counts. A number returned at scan time is a lie by checkout.
- **It crosses aggregates** (product ⋈ inventory) for a value that can't be trusted anyway. A client
  wanting a stock *hint* fetches the existing `GET /inventory/{productId}` and labels it a hint.

So: identity + price here; stock stays behind its own guard.

---

## Scope

### In
- **Repository:** `ProductRepository.findByBarcode(orgId, barcode)` (uses the existing
  `product_org_barcode_unique` partial index; exact match, trimmed, case-sensitive — mirrors the
  `?order_number=` / `?provider_ref=` exact-lookup convention). `barcode` added to the product
  insert/update column set.
- **Service:** `ProductService.getByBarcode(orgId, barcode)` → `Product` or `NotFoundException`;
  `create`/`update` carry `barcode` through with validation (below).
- **API — product resolve:** `ProductHandler` dispatches a `?barcode=` query on the collection GET to
  the single-object lookup (returns one `ProductResponse`, **not** a list — same shape as the `{id}`
  read, same convention as `SalesOrderHandler` `GET /?order_number=`). A bare `GET /products`
  (no param) keeps its existing list behaviour. VIEWER read.
- **API — product write:** `CreateProductRequest`/`UpdateProductRequest` gain optional `barcode`;
  persisted on create/update. STAFF (create/update already STAFF-gated).
- **API — idempotent restock:** `InventoryHandler` reads an optional `Idempotency-Key` header on the
  `restock` action. Backed by a new `inventory_op_idempotency (org_id, idempotency_key, product_id,
  applied_at)` row with `UNIQUE (org_id, idempotency_key)` (migration **V-next**): first call inserts
  the key + applies the `+stock`; a replay with the same key hits the constraint and **returns the
  prior result** instead of re-incrementing. Absent header = today's behaviour (each call applies).

### Validation (barcode)
- Optional; when present: non-blank after trim, ≤ 64 chars (matches `VARCHAR(64)`), and **unique per
  org** — a duplicate is a **409** `"Barcode already exists: <code>"` (Postgres partial-unique
  violation mapped to `ConflictException`, same treatment as the existing SKU conflict). No format
  enforcement — a barcode is an opaque string (EAN-13, UPC-A, Code 128, Code 39 all encode differently;
  the *scanner* decides which symbologies to read, not the DB).

### Out (deferred)
- **No barcode on the sale/restock request bodies.** Lines stay `product_id` (in-store sale
  unchanged); the client resolves first via `?barcode=`. Keeps every write path untouched.
- **No offline queue** — this slice only ships the *idempotency seam* (`Idempotency-Key` on restock)
  the queue will need. The queue itself, Background Sync, and IndexedDB are the frontend's deferred
  work (`16_st_…` §Out).
- **No `adjust` idempotency yet** — `restock` is the offline-receiving path (additive `+delta`);
  `adjust` follows the same seam when the offline queue actually lands.
- **No bulk barcode import** — one barcode per product via the form.

---

## API contract

### Resolve — `GET /api/orgs/{orgId}/products?barcode=<code>`
- **200** → one `ProductResponse` `{ id, name, sku, barcode, base_price, description? }` (identity +
  price; **no stock**).
- **404** → no product with that barcode in the org (the scanner's "unknown barcode" branch → offer
  create-with-barcode-prefilled).
- **400** → blank `barcode` param value.
- **403** → caller lacks VIEWER in the org.

### Write — `POST` / `PUT /api/orgs/{orgId}/products`
- Body gains optional `"barcode": "<string ≤64>"`. Omitted/blank → NULL. Duplicate within org → **409**.

### Idempotent restock — `POST /api/orgs/{orgId}/inventory/{productId}/restock`
- Header `Idempotency-Key: <opaque>` optional. First use applies `+qty` and records the key; a replay
  returns the same `InventoryResponse` without a second increment. Body/behaviour otherwise unchanged.

---

## Concurrency & idempotency
- `findByBarcode` reads through the existing partial unique index — no new lock.
- Barcode write races serialise on `product_org_barcode_unique` (the same backstop as SKU).
- Restock replay races serialise on `inventory_op_idempotency (org_id, idempotency_key)`: the loser of
  a concurrent double-submit sees the unique violation, reads back the winner's row, and returns its
  result — never a second stock move.

---

## Tests

`api/src/test/java/.../product/BarcodeLookupIT.java` + `inventory/RestockIdempotencyIT.java`
(TestContainers Postgres):
- create product with `barcode` → `GET ?barcode=` returns it (identity + `base_price`, **no** stock
  field); second product with the same barcode → **409**.
- `GET ?barcode=<unknown>` → **404**; blank `?barcode=` → **400**; bare `GET /products` still lists.
- `PUT` sets/clears `barcode`; clearing frees the code for reuse (partial index `WHERE barcode IS NOT
  NULL`).
- restock with an `Idempotency-Key`, submitted twice → `stock_qty` rises **once**, both responses
  equal, exactly one `+stock` `inventory_log` row; a *different* key → applies again.
- regression: `ProductServiceTest`, catalog create/update, and `InStoreSaleIT` unaffected (sale still
  takes `product_id`).
