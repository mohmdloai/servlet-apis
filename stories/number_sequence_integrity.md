# Slice: Number-sequence integrity (one owner, no drift, no raw 500)

> An in-store sale for org `1111…1111` crashed with a raw **500**. The trail:
> `claimInvoiceNumber` read `invoice_number_counter.next_val = 1`, formatted `INV-2026-0001`, and the
> `INSERT INTO sales_invoice` was rejected by `sales_invoice_org_id_invoice_number_key` —
> `(org_id, INV-2026-0001)` **already existed**. The counter had drifted *behind* the invoices that
> exist. Two things are wrong: (1) the collision surfaces as an unmapped
> `IntegrityConstraintViolationException` → generic 500 "Unexpected error" instead of a domain error,
> and (2) the drift was allowed to happen at all — an invoice landed in the table without the counter
> being advanced, so every retry now regenerates the same taken number and fails identically (a
> *poison* state, not a transient one). This slice closes both: translate the collision into a clean
> error, and make the counter the **single, unbypassable owner** of `invoice_number` so drift is
> impossible by construction.

---

## Today (the bug)

- **Runtime allocation is already correct.** `SalesInvoiceRepositoryImpl.claimInvoiceNumber` upserts
  the counter row, `SELECT … FOR UPDATE` locks it, returns `next_val`, then `+1` — all inside the
  issuing transaction (`InvoiceService.issueForFulfillment`). If the txn rolls back the increment
  rolls back with it, so the live path is gapless *and* drift-proof **against its own writes**
  (V30's design note; gaplessness is an ETA/tax requirement).
- **The hole is everyone *else*.** `invoice_number` is a plain column any `INSERT` can set. Seed
  scripts, data imports, and test fixtures (e.g. `InvoiceReadsIT`-style direct SQL that pins
  `invoice_number` + `created_at`) write invoices **without touching `invoice_number_counter`**. The
  counter then trails reality. The next real sale claims a number that's already taken → unique-key
  violation.
- **The violation is unmapped.** Unlike the product-delete FK case (translated to a 409
  `ConflictException`), this `IntegrityConstraintViolationException` falls through
  `SalesOrderHandler`'s generic `catch (Exception)` → logged "Unexpected error" → **500**. The
  caller gets an opaque server error with no signal that the fix is "realign the counter", and the
  whole in-store sale (order → invoice → payment → fulfillment) rolled back.

---

## Goal

1. **No raw 500 on an invoice-number collision.** A duplicate `(org_id, invoice_number)` becomes a
   **409 `ConflictException`** with an operator-actionable message, not an "Unexpected error" 500.
2. **Drift is impossible by construction.** Exactly one place in the system allocates and stamps
   `invoice_number`; nothing else — no repository path, migration, seed script, import, or ad-hoc
   SQL — may insert a `sales_invoice` row with an explicit number. Data imports go through a
   dedicated routine that advances the counter in the same breath.
3. **A drifted counter is detectable and repairable** without guesswork (realign to
   `MAX(seq for org/year) + 1`), so an already-diverged environment can be healed once.

Done means: the in-store sale that 500'd now either succeeds (counter is the sole source of truth)
or, in a legacy-diverged DB, fails with a clear 409 that names the remedy — and no new code path can
re-introduce the divergence.

---

## Design

### Minimum improvement — translate the exception (blocking)

Map the number unique-constraint violation to the existing domain hierarchy so it exits as a
409, matching how the product-delete FK violation already maps to `ConflictException`:

- Catch the `IntegrityConstraintViolationException` on the `sales_invoice` insert (narrow it to the
  `_org_id_invoice_number_key` constraint — a *different* constraint must not be swallowed as this
  message) and rethrow as `ConflictException("Invoice number sequence is out of sync — contact
  support.")`.
- **The same gap exists on credit notes** — confirmed, not hypothetical (see *Detect & repair*): the
  `credit_note` insert on `_org_id_credit_note_number_key` is likewise unmapped, so a
  `claimCreditNoteNumber` collision surfaces as an "Unexpected error" 500 too. Translate it the same
  way (`"Credit-note number sequence is out of sync — contact support."`). Both translations are the
  smallest change and worth shipping on their own before the structural work below.
- The message is operator-facing on purpose: the caller can't fix it, but support can (realign the
  counter).

### Stronger design — one owner for `invoice_number` (the real fix)

The allocator already exists; the gap is that it isn't the *only* writer. Make it so:

- **`InvoiceNumberAllocator` is the single source of numbering.** Every issue path (delivery invoice,
  in-store sale invoice — and by the same argument credit-note and order numbering, which share the
  identical counter pattern in V27/V31) obtains its number from the allocator and never formats or
  assigns one itself. `InvoiceService` already does this via `claimInvoiceNumber`; the change is to
  make that the *enforced* contract, not a convention.
- **No explicit `invoice_number` on any other insert.** Repository inserts, migrations, seed data,
  and test fixtures may not set `invoice_number` directly. The allocate-then-insert pair
  (`claim` → `INSERT` in one txn) is the only sanctioned way an invoice acquires a number, so the
  counter can never trail the table.
- **Imports have a dedicated routine that also advances the counter.** Backfilling historical
  invoices (with pre-existing numbers) is a legitimate need, but it goes through one import path that
  bumps `invoice_number_counter` to `MAX(imported seq) + 1` in the same transaction — so an import
  can't leave the counter behind. This is the *only* place allowed to write a number the allocator
  didn't mint.
- **Result:** there is exactly one owner of invoice numbering in the whole system, and drift stops
  being reachable — the 409 above becomes a legacy-data safety net rather than an expected outcome.

### Detect & repair (one-time heal for already-drifted envs)

A small reconciliation check/routine — per `(org_id, year)`, assert
`counter.next_val > MAX(numeric suffix of the number in the table)`; where it fails, realign
`next_val` to `MAX + 1`, forward-only (`GREATEST(current, MAX+1)`, so an already-ahead counter is
never lowered). Runs as a guarded admin/maintenance action. (Invoice/credit-note numbering is
gapless by design, so realigning forward never reuses or skips a live number.)

**Confirmed field state (org `1111…1111 / 2026`), and what the repair actually did:**
- **Invoice — was poisoned, repaired.** `INV-2026-0001` existed with an *empty*
  `invoice_number_counter`, so every issue restarted at `0001` and collided (the 500 that opened this
  story). Realigned `next_val → 2`; subsequent sales now mint `0002`, `0003` cleanly.
- **Credit-note — same poison, repaired.** `CN-2026-0001` existed with an *empty*
  `credit_note_number_counter` → identical latent 500 on the next credit note. Realigned `next_val → 2`.
- **Order — a naive int check is a FALSE POSITIVE; do NOT bump it.** The allocator is `SO-%d-%05d`
  (5-digit) while seed orders are 6-digit (`SO-2026-000101…000103`). `%05d` can never emit a
  6-digit-with-leading-zero string, so the minted and seeded namespaces are **disjoint** — no
  collision is reachable and `next_val` is correctly left at `3`. A repair keyed only on the parsed
  integer (`3 < 103`) would wrongly jump it to `104`. **Lesson for the repair routine: compare on the
  formatted string / same padding, not the bare integer**, or scope it to counters whose format has
  never changed (invoice/credit-note, both `%04d`).

---

## Errors

| Status | Cause |
|---|---|
| `409` | invoice- **or** credit-note-number collision on issue — counter drifted below the table (message names the remedy) |
| `500` | **eliminated** for both cases — the collision no longer escapes as an "Unexpected error" |

(Once the stronger design lands, the 409 is only reachable in a legacy-diverged database; a
greenfield DB where every number came from the allocator cannot hit it.)

---

## Tests

- **Collision → 409, not 500** (component / IT): with the counter forced behind an existing
  `INV-YYYY-0001`, issuing an invoice (in-store sale or delivery) surfaces a `ConflictException`
  (409), the transaction rolls back cleanly, and no partial order/payment/fulfillment survives. **The
  same test for credit notes** — counter behind an existing `CN-YYYY-0001`, issuing a credit note is a
  409, not a 500.
- **Allocator is the sole writer** (guard/architecture test): no `sales_invoice` insert path outside
  the allocator sets `invoice_number`; the sanctioned issue path advances the counter exactly once
  per invoice and is gapless under a rolled-back txn (extends the existing gaplessness test).
- **Import advances the counter** (IT): the dedicated import routine leaves
  `next_val = MAX(imported) + 1`, so a subsequent live issue does not collide.
- **Repair realigns** (IT): given a seeded divergence, the reconciliation routine moves `next_val`
  to `MAX + 1` and a following issue succeeds.

---

## Out of scope / known gaps

- **Credit-note (V31) is in scope** now that field data confirmed it is the *same active defect*, not
  a latent one — it gets the same 409 translation, the same single-owner rule, and shares the repair
  routine. Order-number (V27) shares the counter *pattern* but is **not currently vulnerable to the
  seed data** (the `%05d` vs 6-digit padding disjointness above); the single-owner principle should
  still extend to it for consistency, but there is no active order-number collision to fix — track it
  as a lower-priority follow-up.
- **Latent order-format risk:** the docs example is 6-digit (`SO-2026-000123`) while the code emits
  5-digit (`SO-%05d`); the seed orders were generated against the old 6-digit format. Harmless today
  (disjoint namespaces), but if the order format is ever widened back to 6 digits the seeds re-enter
  collision range — reconcile the order counter (and the docs) as part of any such format change.
- No change to the gapless guarantee or the `INV-YYYY-NNNN` / `CN-YYYY-NNNN` format; this is about
  *who* may allocate, not *how* the number looks.
- The repair routine is a maintenance action, not a migration — it heals data, and running it is an
  operator decision (a migration can't safely assume which side, table or counter, is authoritative
  in an arbitrary environment).
