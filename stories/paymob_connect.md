# Slice: Connect a Paymob merchant account (per-org credentials)

> Slice 1 of [`docs/paymob-card-epic.md`](../docs/paymob-card-epic.md). **Branch
> `190_feat/paymob-connect` off `master`, migration V98.**
>
> This slice takes **no money**. It stores the credentials and teaches the storefront to say
> "card accepted", so that [`paymob_card_checkout.md`](paymob_card_checkout.md) has something to
> authenticate with. Splitting it out is deliberate: credential storage is the part with a security
> invariant to get right, and it is testable on its own.

---

## Goal

An org **OWNER** connects the org's own Paymob merchant account. The secret key and HMAC secret are
stored as AES-GCM ciphertext and never leave the server again — not to the merchant who typed them,
not to a platform ADMIN. The storefront then advertises `card` alongside the InstaPay instructions
it already shows.

`GET|POST|DELETE /api/orgs/{orgId}/paymob`

Done means: an OWNER POSTs four credentials, gets back a status body with **no secrets in it**, and
`GET /api/public/{orgSlug}` starts listing `card` in its payment methods. An org that never connects
is byte-identical to today.

---

## Why per-org, and what it costs

The epic records the decision (§"The two owner decisions"): the shopper's money goes to the
merchant's Paymob account, never to the platform. `org.instapay_handle` (V52) already established
that shape — this is the same rail with a PSP in front of it.

The cost is that a credential which can **charge cards as the merchant** now lives in our database.
That is the second per-tenant secret this system has stored, and the first one — `org_whatsapp_config`
(V82) — already wrote the rules:

> `access_token_encrypted` is ciphertext, never the raw token: AES-GCM under a platform key from the
> `WHATSAPP_TOKEN_KEY` env var (see `common/crypto/SecretBox`). The column is named for what it holds
> so that a future reader cannot mistake it for a plaintext credential, and no read path returns it.

This slice follows that precedent exactly, including the naming convention (`*_encrypted`) and the
fail-closed rule: **no key configured → connect refuses**, rather than storing a card credential in
the clear.

---

## Scope

### In
- **V98**: `payment_provider` += `paymob_card`; new table `org_paymob_config`.
- Domain: `OrgPaymobConfig` record (+ `Status` enum), mirroring `OrgWhatsAppConfig`.
- Repository: `OrgPaymobConfigRepository` (+ factory) — PK-only access, no index.
- Service: `OrgPaymobService` with `status / connect / setEnabled / disconnect / activeConfig`,
  mirroring `OrgWhatsAppService` method-for-method.
- Handler: `PaymobHandler`, registered in `OrgServlet`'s dispatch map under `"paymob"`.
- Storefront: `StorefrontService` org profile gains `payment_methods: ["instapay","card"]`, `card`
  present iff an ACTIVE config exists.
- Env: `PAYMOB_CREDENTIAL_KEY` (Base64, 32 bytes) — its own key, not `WHATSAPP_TOKEN_KEY`.

### Out (deferred)
- **No intention, no checkout, no webhook** — slice 2.
- **No credential validation against Paymob.** Connect stores what it is given; a wrong key surfaces
  as a failed intention in slice 2. A live "test connection" call is a slice-3 nicety, and doing it
  here would mean slice 1 could not be tested without a Paymob sandbox.
- **No key rotation.** `SecretBox` states the scope plainly and this slice does not widen it: one
  platform key, one env var, no `key_version` column. Rotation needs a key id per row and a
  re-encrypt job — real work nobody has asked for, and an unused version column would be worse than
  the honest absence.
- **No in-store card** (epic slice 4 — needs a terminal, shares only the word "card").
- **No wallet / Apple Pay / BNPL.** The config stores one `card_integration_id`. Extra methods are
  extra integration ids; the column becomes a list when a second one is actually wanted.

---

## Data model — V98

```sql
ALTER TYPE payment_provider ADD VALUE IF NOT EXISTS 'paymob_card';
```
Same one-line shape as V37 and V93 (the two prior enum additions). PostgreSQL 17 permits
`ADD VALUE` inside Flyway's transaction, but **the new value cannot be used in that same
transaction** — which is why this migration adds the value and nothing else. The first row using it
is written by slice 2, several migrations later.

```sql
CREATE TABLE org_paymob_config (
    org_id                 UUID PRIMARY KEY REFERENCES org(id) ON DELETE CASCADE,

    -- Public by design: this one is handed to the shopper's browser to open Unified Checkout.
    public_key             TEXT NOT NULL,

    -- Charges cards as the merchant. AES-GCM under PAYMOB_CREDENTIAL_KEY. Never read back out.
    secret_key_encrypted   TEXT NOT NULL,

    -- Verifies webhook signatures. Equally fatal if leaked: whoever holds it can forge a
    -- "payment succeeded" this system would believe. Same treatment, same reason.
    hmac_secret_encrypted  TEXT NOT NULL,

    -- Which of the merchant's Paymob integrations to charge. Capture-on-sale (see the epic's
    -- §Pending and auth-only) — an auth-only integration will not settle and slice 2 warns.
    card_integration_id    INTEGER NOT NULL,

    -- Paymob is regional and the host differs per country (accept./uae./ksa./oman./pakistan.).
    -- Stored rather than global: a platform serving two countries has orgs in both.
    region                 TEXT NOT NULL DEFAULT 'EGYPT' CHECK (region IN ('EGYPT')),

    status                 TEXT NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    connected_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`region` ships with a single permitted value on purpose. The column exists because the host is a
real per-org fact and discovering that after go-live means a migration plus a backfill against live
merchants; the CHECK is narrow because every other region is unverified. Widening it is one line the
day an org is actually onboarded elsewhere.

A separate table rather than columns on `org`, for V82's reason repeated: an integration with its
own lifecycle that most orgs will never have should not widen the hottest row in the schema with
six mostly-NULL columns.

---

## API contract

### `POST /api/orgs/{orgId}/paymob` — connect (or replace)
```json
{
  "public_key":          "egy_pk_test_...",
  "secret_key":          "egy_sk_test_...",
  "hmac_secret":         "6A1B...",
  "card_integration_id": 4938201,
  "region":              "EGYPT"
}
```
Upsert: re-POSTing replaces every credential and stamps `updated_at`. There is no PATCH — a partial
credential update is a way to end up with a secret key from one account and an HMAC secret from
another, which fails at the worst possible moment (a real payment, in production, silently
unverifiable).

### Response — `200 OK` (the same body `GET` returns)
```json
{
  "connected":           true,
  "status":              "ACTIVE",
  "public_key":          "egy_pk_test_...",
  "card_integration_id": 4938201,
  "region":              "EGYPT",
  "connected_at":        "2026-09-14T08:00:00Z",
  "updated_at":          "2026-09-14T08:00:00Z"
}
```
**`secret_key` and `hmac_secret` appear in no response, ever.** Not masked, not truncated —
absent. `public_key` is returned because the browser needs it to open Unified Checkout.

`GET` on an unconnected org returns `200 {"connected": false}` — absence is a state, not a 404, so
the settings screen renders the same way either way.

### `DELETE /api/orgs/{orgId}/paymob` — disconnect
`204`. Deletes the row. Idempotent — deleting an absent config is also `204`. Existing
`payment_transaction` rows with `provider = 'paymob_card'` are untouched: the ledger records money
that really moved, and disconnecting a gateway does not un-charge a card.

### Errors
| Status | Cause |
|---|---|
| `400` | missing/blank credential, `card_integration_id` not a positive integer, unsupported `region` |
| `403` | caller lacks **OWNER** in `:orgId` (system ADMIN bypasses) |
| `409` | `PAYMOB_CREDENTIAL_KEY` unset — *"cannot store payment credentials: no encryption key configured"* |
| `405` | `PUT`/`PATCH`, or any `/paymob/...` sub-path |

---

## Authorization — why OWNER, not MANAGER

`requireOrgAccess(orgId, OWNER)`. Every other payment surface in this system is MANAGER: verifying a
claim, resolving an orphan, recording a transfer. Those move money that has already arrived. This
one decides **where the org's card revenue is deposited** — a MANAGER who can repoint it at their
own Paymob account is a MANAGER who can take the shop's income. The WhatsApp connect endpoint is
MANAGER because the blast radius is embarrassing messages; this one is the bank details.

---

## Acceptance criteria

- [ ] OWNER POSTs four valid credentials → `200`, row exists, `status = ACTIVE`; the response body
      contains `public_key` and **neither** `secret_key` **nor** `hmac_secret`.
- [ ] The stored `secret_key_encrypted` and `hmac_secret_encrypted` are **not** the plaintext, and
      `SecretBox.decrypt` returns the originals.
- [ ] `GET` before any connect → `200 {"connected": false}`; after connect → the status body; after
      `DELETE` → `{"connected": false}` again.
- [ ] Re-POST with new credentials replaces all four and bumps `updated_at`; exactly one row.
- [ ] MANAGER (not OWNER) → `403`. VIEWER → `403`. Another org's OWNER → `403`.
- [ ] With `PAYMOB_CREDENTIAL_KEY` unset, connect → `409` and **nothing is written** (assert the
      table is empty — a fail-open here would store a card secret in the clear).
- [ ] `card_integration_id: 0` / `-1` / `"abc"` → `400`. `region: "KSA"` → `400`.
- [ ] `GET /api/public/{orgSlug}` lists `card` in `payment_methods` iff an ACTIVE config exists, and
      lists `instapay` exactly as it does today in both cases.
- [ ] `DELETE` on an org with existing `paymob_card` transactions → `204`, and the transaction rows
      are still there.
- [ ] A `paymob_card` value exists on the `payment_provider` enum after V98 (guards the migration
      against a silent no-op from `IF NOT EXISTS`).
