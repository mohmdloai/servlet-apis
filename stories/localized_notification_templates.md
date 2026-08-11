# Notifications in the language the shopper reads (slice L)

> **Slice L of the notification-reach epic** (`frontst/docs/notification-reach-epic.md`). Ranked
> above the WhatsApp channel deliberately: a message that arrives reliably and is written in a
> language the recipient does not read has not solved reach.

## The problem

Every notification title and body was rendered **server-side in English**, for all seven types. An
Arabic-locale shopper got an Arabic storefront, an Arabic order page, an Arabic UI — wrapped around
an English sentence:

```
عرض الطلب  ← the button
"Order SO-2026-00041 has shipped"  ← the message above it
```

The content-localization epic (L1–L6, merged) built per-language translation tables for **merchant
content** — listings, categories, banners, pages — and never reached `NotificationTemplates`. L2b
even resolves the checkout locale to snapshot the order-line title in it, then **discarded the
locale**, so nothing downstream could use it.

`org.default_locale` defaults to `'ar'` (V52), i.e. the typical store is Arabic. So this was not an
edge case; it was the default experience.

## What ships

### `V81` — `customer.locale`

Nullable `VARCHAR(5) CHECK (locale IN ('ar','en'))` — the same set as `org.default_locale`, because
the two are compared and substituted for each other constantly and a locale set that can drift
between them is a bug waiting for its first third language.

**Null is a real answer**: "we have never learned it", which resolves to the org default. That is
why there is no column default — `'ar'` here would claim knowledge we do not have, and would be
wrong for an English-default store. No index, no backfill: nothing queries customers *by* locale,
and inferring a language from a name or an address would be a guess about a person.

### `common/text/Locales` — one definition

`SUPPORTED`, `normalize` (accepts and drops a region subtag, `ar-EG` → `ar`), `resolve`,
`direction`. `SUPPORTED_LOCALES` + a near-identical `resolveRequestedLocale` are currently declared
privately in **six** services; adding a seventh copy for notifications is exactly the drift this
codebase keeps structurally impossible elsewhere, so the new code consumes this instead.

`resolve` is **total** — never throws, never returns null, floors to Arabic. It is called from
inside business transactions, where an unrecognised stored value must pick a language rather than
roll back an order.

### Locale resolution: `customer.locale` → `org.default_locale` → `ar`

`NotificationService.resolveLocale` does this per notification, and **must not throw** for the same
reason `resolveCustomerEmail` must not: `notify` runs inside the caller's *business* transaction, so
an exception would roll back the order placement, not merely pick the wrong language. Both reads are
individually guarded and degrade to the floor.

A **USER** (staff) recipient resolves to the org default — `app_user` carries no locale, and an
org's staff notifications reasonably follow the store's own language. Costs one small org read per
notification, deliberately not cached: these fire at human cadence inside transactions already doing
far more work, and a stale locale cache would be a much worse bug than a redundant PK lookup.

### Both locales, for all seven types

`NotificationTemplates.render` takes the locale; every title, body and CTA label exists in Arabic
and English. The CTA wording matches the storefront's own `ar.json` ("عرض الطلب", "قيّم منتجاتك",
"أكمل الدفع"), so an email's button and the page it opens say the same thing.

`emailHtml` now wraps the body in `<div lang dir>`. **Without `dir="rtl"` most mail clients lay
Arabic out left-to-right**, which mangles the punctuation and any Latin token inside the sentence —
and an order number sits mid-sentence in almost every one of these messages. The unsubscribe footer
is translated too; an Arabic email with an English "Unsubscribe" is the same defect in miniature.

### The emailed link follows the message

`MagicLinkService` resolves the customer's locale for the `/{locale}/{orgSlug}/…` segment, so an
Arabic email's only button lands on the Arabic page. It previously used the org default with an
`"en"` fallback constant, which is now gone. This closes the loop the slice exists for — a
translated message that opens an English page is still a half-translated experience.

### Where the locale comes from

| Source | Semantics |
|---|---|
| Anonymous checkout | **fill-once** on the customer upsert |
| Portal checkout | **fill-once** via a narrow `fillLocaleIfAbsent`, inside the placement txn |
| `PATCH /api/portal/me` | **explicit — overwrites**; an unsupported tag is a 400, not a silent ignore |
| In-store sale | nothing; the counter has no storefront locale |

**Fill-once, not last-write-wins**, and that is the load-bearing choice. A checkout locale is an
*implicit* signal — whichever link the shopper happened to open — while the portal profile is
*explicit*. Letting one English checkout permanently flip an Arabic speaker's language is the same
class of mistake as the delivery contact overwriting their identity (V80), and this slice lands
right after that one. The portal path uses a **narrow named verb** rather than the general update
for the same reason: a checkout must not be able to touch any other identity field.

On the upsert the argument order is deliberately **reversed** from its neighbours —
`COALESCE(customer.locale, EXCLUDED.locale)`, stored value wins — and it is commented at the call
site, because it reads like a mistake next to three lines that do the opposite.

## The behaviour change to know about

**Any org that never set `default_locale` now sends Arabic notifications**, because V52's column
default is `'ar'`. That is correct — such a store already serves an Arabic storefront to its
shoppers — but it is a visible change on merge, not a no-op.

Seven existing ITs asserted English strings while seeding orgs that left the column unset; they now
set `default_locale = 'en'` **explicitly**, with a comment saying why. They test notification
mechanics, not wording, so pinning the locale keeps them readable rather than restating every
template in Arabic — the Arabic path has its own coverage.

## Acceptance criteria

- [x] Every one of the seven types renders in Arabic — title, body **and** CTA — with no English
      leaking through, and renders differently per locale (a missing translation fails the test).
- [x] `customer.locale` beats `org.default_locale`; an unset customer follows the store; an unknown
      or null locale floors to Arabic instead of throwing.
- [x] An Arabic email declares `lang="ar"` / `dir="rtl"` and carries an Arabic unsubscribe footer.
- [x] An Arabic email's magic link points at `/ar/…`; an English customer at an Arabic store gets
      `/en/…`.
- [x] Optional payload fields (carrier, tracking, refund total) stay conditional in Arabic too —
      no `null` ever renders.
- [x] Anonymous and portal checkout learn the locale once and never overwrite it; `PATCH /me` sets
      it explicitly and 400s an unsupported tag.

## Tests

`NotificationTemplatesTest` — 22, including two `@EnumSource` sweeps that force **every** type
through both locales, so a type added later without an Arabic branch fails here rather than reaching
a shopper. One of them caught a real bug during the build: `render` floored an unknown locale to
English while `emailHtml` floored to Arabic, which would have put an English body inside an RTL
wrapper. Both now share one floor.

`OrderLifecycleNotificationIT` — the end-to-end Arabic path (feed row, email subject, `dir="rtl"`,
`/ar/` link) and the customer-overrides-store case. **Full backend sweep: 1042 ITs green**; unit
modules common 57, service 230.

## Out

- **The voice of `ORDER_PLACED`.** It is the one type sent to both org staff and the customer, and
  its wording is merchant-facing ("New order SO-…") for both. The Arabic is a faithful translation
  of that, wart included: splitting a template per recipient type is a real improvement and a
  different change, and translating a wart is honest where quietly rewriting customer-facing copy
  inside a localization slice would not be.
- **Migrating the six existing `SUPPORTED_LOCALES` copies** onto `Locales`. Mechanical and low risk,
  but it touches six services and their tests, and expanding a localization slice into a
  cross-service refactor is how a reviewable diff stops being one.
- **A staff locale.** `app_user` has none; staff follow the org. Nobody has asked for per-user
  language, and it needs a settings surface.
- **A third language.** The CHECK constraints, `Locales.SUPPORTED` and the templates are the three
  places that would change; the resolution logic would not.
- **Re-rendering existing notifications.** Rows already produced keep their English text. An email
  that has been sent cannot change language, and the portal feed deliberately shows the same words
  the email did.
