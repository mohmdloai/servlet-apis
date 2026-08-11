# Phone as real data — E.164 normalization (`customer.phone_e164`)

> **Slice N of the notification-reach epic** (`frontst/docs/notification-reach-epic.md`), sitting
> between the shipped events (Slice A) and the WhatsApp channel (Slice B). It is a **gate, not a
> refactor**: without it there is no value to hand a messaging provider.

## The problem

`customer.phone` looks like a phone number and is not one.

```sql
customer.phone   TEXT   -- V28: free text, no validation anywhere, ever
```

It is written through `Text.normalizeNumeric`, which folds Arabic-Indic/Persian digits to ASCII and
strips invisible controls — genuinely valuable — but which **deliberately keeps non-digits** and only
collapses whitespace. Its own Javadoc says so: *"a phone may legitimately carry `+` — so this is not
a 'digits only' filter."* Which means all of these are live, valid values for the same person:

```
01012345678      +20 100 123 4567      0100-123-4567      ٠١٠١٢٣٤٥٦٧٨      00201012345678
```

WhatsApp needs `201012345678`. Nothing in the codebase could produce it.

**The timing is a gift that expires.** There is no real corpus to migrate yet: the dev database has
**zero** customer phones and `perfdb`'s 200 001 seeded rows are uniform synthetic values. This slice
costs a migration and a parser today; after a year of real orders it would cost those plus a
data-quality project.

## What ships

### `common/.../text/Phone.toE164` — the parser

A stateless, **JDK-only** utility beside `Text` (whose `normalizeNumeric` it runs first, so an
Arabic-keyboard number canonicalizes identically to its ASCII twin). Rules, in order:

| Input shape | Reading | Result |
|---|---|---|
| `+…` | explicitly international | as given |
| `00…` | international access code | strip `00` |
| `0…` | national trunk prefix | strip `0`, prepend `20` |
| `20` + valid EG NSN | country-coded, missing the `+` | as given |
| a valid EG NSN | national, trunk prefix omitted | prepend `20` |
| anything else | **would require inventing a country code** | `null` |

Then a plausibility check: 8–15 digits (E.164's cap), never a leading `0`, and — if it claims to be
Egyptian — held to Egypt's own plan (mobile `1[0125]` + 8 digits; landline 8–9 digits behind a
`2`–`9` area code).

**Egypt-first by decision.** A worldwide parser means libphonenumber's metadata in `common`, the
module every other module inherits, for one field. The market is Egypt; an already-international
number still canonicalizes on a length check; and swapping the body of `toE164` for libphonenumber
later is a one-class change, because nothing else reads the rules.

**It returns `null` rather than guessing.** A bare `4155550132` is neither trunk-prefixed nor marked
international nor Egyptian — assigning it a country code would be an invention, and a
plausible-looking wrong number in a column whose entire purpose is to be dialable is worse than an
honest "unknown". This is the one place the parser is deliberately less clever than it could be.

### `V79` — `customer.phone_e164`, nullable

**Two columns, not an in-place rewrite.** This mirrors the split V62 already established between a
faithful *storage* form and a derived *match* form (`customer.name` / `customer.name_search`):
`phone` keeps exactly what the shopper typed — that is what a support agent reads back on a call —
and `phone_e164` is the machine form. A re-parse can therefore never lose the original, and
improving the parser later is a backfill rather than a data-loss event.

**Nullable, and null is a real answer**: "we could not turn what they typed into a dialable number",
i.e. a suppressed channel, exactly like a customer with no email address. `NOT NULL` would be a lie
given the fail-open decision below.

**No index.** Nothing looks a customer up *by* phone — Slice B will resolve a number from a
customer id, not the reverse — and this codebase does not add unmeasured indexes (the V73 rule; V77
even *removed* a story's proposed index after measuring). Phone is also deliberately **not**
searchable (`CustomerReadsIT.search_doesNotMatchPhone`), and that is unchanged here.

### The backfill

Runs once, inside V79. It mirrors the Java, but is **deliberately narrower**: it canonicalizes only
the unambiguous Egyptian mobile shapes and already-`+`-prefixed numbers, leaving everything else
`NULL` for the parser to pick up the next time that customer's record is written. A backfill that
guesses is worse than one that under-reaches, because a wrong number here is a message sent to a
stranger.

Drift between the SQL and the Java is a non-issue **by construction**: the SQL runs exactly once, in
this migration, and every ingress writes the column from Java. Nothing recomputes it in the database.

**Measured, not assumed.** Dry-run against `perfdb` (read-only `SELECT`, no write): the first draft
canonicalized **200 000 of 200 001** rows. The one holdout was `1092937220` — an Egyptian mobile with
the trunk prefix omitted, a shape the Java handles unambiguously and the SQL did not. That case was
added (it is a rule, not a guess) and coverage is now **200 001 / 200 001**.

### Where it is written

**In the repository, not the service** — so no write path can persist a phone without its dialable
twin:

| Site | Path |
|---|---|
| `SalesOrderRepositoryImpl.upsertCustomerByEmail` | anonymous + portal checkout, admin phone order, delivery-contact freeze |
| `CustomerRepositoryImpl.insert` / `update` | CRM writes, and the portal's `PATCH /api/portal/me` |

**The upsert needed its own rule, and this is the subtle part.** `upsertCustomerByEmail` merges with
`COALESCE(excluded, current)` so a null incoming field preserves what is there. Applying that to
`phone_e164` would be a bug: when the incoming phone is **present but unparseable**, COALESCE keeps
the *old* E.164 while `phone` is overwritten — leaving the pair describing two different people, and
the stale one is the number a message would actually be sent to. So the twin follows the phone's
fate exactly:

```sql
phone_e164 = CASE WHEN excluded.phone IS NOT NULL THEN excluded.phone_e164 ELSE customer.phone_e164 END
```

Pinned by `replacingAGoodPhoneWithAnUnparseableOne_clearsTheTwinRatherThanKeepingTheOldNumber`.

## Decisions

### Fail open — a bad phone never rejects anything

Store what they typed in `phone`, leave `phone_e164` null, place the order. This is the **D3
precedent** verbatim: a customer with no email address is a *suppressed channel*, not a failed
business event, and `notify()` was specifically changed to stop throwing there. It is also
`EmailGate`'s posture at checkout (log, never block).

The alternative — 400 on an unparseable phone — makes a parser gap into a lost sale at the one place
this system must never be brittle. **A rejection policy remains available later** as an explicit
owner decision; nothing here forecloses it, and `phone_e164 IS NULL` is exactly the signal a future
UI would key on to say "we can't reach you on WhatsApp".

### Not exposed on any API yet

No DTO carries `phone_e164`. It is an internal derived column with no consumer until Slice B, and
this codebase does not ship API surface ahead of a caller (the notifications plan's own reflex:
*"a standalone `CustomerService.upsertByEmail` was not added — it would be an uncalled method until
a slice needs a direct entry point"*).

### Out of scope, on purpose

- **`customer_address.phone`** — a per-address delivery contact for the courier to call, not the
  customer's messaging identity. Slice B sends to the customer. Worth doing when a rider actually
  needs to dial it (`rider_self_delivery.md`).
- **`sales_invoice.customer_phone`** — a **frozen legal contact snapshot** (V21, "in case the
  customer record changes later"). Retroactively rewriting an immutable document record to a
  prettier format is how an invoice stops being evidence. Never backfill it.
- **`org.phone`** — the merchant's own number, a billing-profile display field. Slice B may need a
  canonical form of it for the WABA sender identity; that belongs with the onboarding flow.
- **`phone_verified`** — nothing proves the number belongs to the person. It is a contact field, not
  an identity claim, and must not become one without an OTP flow (the email plane's own rule).

## Acceptance criteria

- [x] `01012345678`, `+201012345678`, `00201012345678`, `201012345678`, `1012345678`, spaced,
      dashed, parenthesised, Arabic-Indic and Persian spellings all canonicalize to `+201012345678`.
- [x] All four Egyptian mobile prefixes (010/011/012/015) round-trip; unassigned 013/014 are `null`.
- [x] Cairo and governorate landlines canonicalize; a `0`/`1` "area code" is rejected.
- [x] Explicitly international numbers pass through; a bare foreign number is `null`, not a guess.
- [x] Garbage (`""`, `"abc"`, `"call me"`, `"12345"`, 16 digits) returns `null`, never throws.
- [x] `toE164` is idempotent, so the backfill is safely re-runnable.
- [x] Checkout writes the typed value **and** the twin; an unparseable phone still places the order.
- [x] Replacing a good phone with an unusable one **clears** the twin rather than keeping the old
      number; a null incoming phone preserves both.
- [x] The portal profile edit re-derives the twin and clears it when the new number is unusable.

## Tests

`common/.../text/PhoneTest` — 30 unit tests (the shapes actually live in the column today).
`CustomerResolutionAtPlacementIT` — 5 new cases on the checkout upsert, including the
stale-twin guard. `PortalAuthIT` — the profile-edit re-derivation.
Regression: **232 green** across the customer, portal, checkout, notification and platform-search
ITs (`PlatformSearchIT` included deliberately — it pins that no customer PII crosses the cross-org
boundary, and a new customer column is exactly the kind of change that could break it).
Unit modules: common 57, service 212.

## What this unblocks

- **Slice B** — the WhatsApp channel now has something to dial.
- **Rider SMS** — `rider_identity_and_linking.md` notes SMS delivery of rider OTPs/invites as "the
  obvious future" and had the identical blocker.
