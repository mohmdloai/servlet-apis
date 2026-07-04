# Slice: Look up a transaction by provider reference (G6 — nice-to-have)

> Follow-up filter on the read slice shipped by
> [`list_payment_transactions.md`](list_payment_transactions.md) (depends on it). The support
> flow it serves: a customer says *"I paid — reference IP-778899"*, or an InstaPay reference
> stares out of a bank statement, and the admin needs to know **did we record this, and what
> happened to it?** Today `findByProviderRef` exists on the repository but is reachable only as
> the write-path idempotency check inside `POST /payment-transactions`.

---

## Goal

`GET /api/orgs/{orgId}/payment-transactions?provider_ref=IP-778899`

Done means: pasting a reference answers "recorded or not" in one call, and the returned row's
`verification_status` / `reconciliation_status` (plus `has_payment` composition or the `/{id}`
detail from the parent slice) says what happened to the money next.

---

## Semantics

Two new **list filters**, composing with everything the parent slice shipped (ANDed, same
ordering rules, same `PageResponse` envelope):

| Param | Values | Meaning |
|---|---|---|
| `provider_ref` | free text | exact match after trimming (references arrive by copy-paste); case-sensitive — it is the provider's identifier, not user prose |
| `provider` | `PaymentProvider` enum name or DB literal (e.g. `INSTAPAY_MANUAL` / `instapay_manual`) | literal column match; unknown value → 400 |

- The natural key is `UNIQUE(provider, provider_ref)` **globally**, so within one org
  `provider_ref` alone returns at most a handful of rows (same ref across different providers) —
  in practice 0 or 1. It stays a *list* filter rather than a `/by-ref/{ref}` lookup route
  because references are provider-scoped, not a standalone identity, and the empty-list "not
  recorded" answer is exactly what the support flow needs (a 404 would conflate "no such route"
  with "not recorded").
- A reference recorded under **another org** returns an empty list, not that org's row —
  standard scoping; the global natural key never leaks across tenants.
- No repository addition beyond widening the parent slice's `ListFilter` with the two nullable
  fields; `findByProviderRef` (global, unscoped) remains the write-path's internal check and is
  deliberately **not** exposed.

---

## API contract

```
GET /api/orgs/{orgId}/payment-transactions?provider_ref=IP-778899
```

`200 OK` — the parent slice's `PageResponse` of transaction rows; `data: []` means "never
recorded here". Errors as in the parent slice (400 unknown `provider`; 403 non-member).

---

## Authorization

Inherited from the parent slice: `requireOrgAccess(orgId, VIEWER)`.

---

## Scope

### In
- `provider_ref` + `provider` fields on `ListFilter` (+ jOOQ predicates).
- Param parsing in the existing `GET` list branch.
- Docs: CLAUDE.md endpoint list (filter mention).

### Out (deferred)
- **Prefix/fuzzy reference search** — exact match only; mangled references are a search-screen
  feature.
- **Cross-org platform-admin reference lookup** ("which org received IP-778899?") — a
  `/api/admin` concern with its own authz story, if support ever needs it.

---

## Tests

Extend `PaymentTransactionReadIT` (parent slice):
- recorded reference → exactly that row; unknown reference → empty `data`, `total: 0`.
- whitespace-padded input matches; case-mismatched input does not.
- same reference in another org → empty (tenant scoping over a global natural key).
- `provider_ref` + `has_payment=true` composes (the "what happened to it" follow-up).
- `?provider=BOGUS` → 400.
