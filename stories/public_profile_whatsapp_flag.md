# `whatsapp_enabled` on the public storefront profile

> Small follow-on to `stories/whatsapp_channel.md` (slice B). **No migration** — it reads V82's
> `org_whatsapp_config`. Exists to unblock the customer-facing WhatsApp opt-out in the portal.

## Why

`GET /api/public/{orgSlug}` had no way to say whether a store has a live WhatsApp channel. Without
it the portal's notification settings cannot tell a store that never connected from one that did,
so it would have to offer a WhatsApp opt-out to **every** shopper — a switch that, for most stores,
turns off a channel that could never send. A setting that does nothing is worse than no setting.

## What ships

`StorefrontProfileView.whatsappEnabled` → `whatsapp_enabled` on the wire.

- **A primitive, so it is always on the wire.** Jackson omits nulls, and a boxed `Boolean` would
  make absence mean "no WhatsApp" — indistinguishable from an older backend that never sent the
  field. Same both-states-explicit reflex as `email_verified` on the admin user DTO.
- **True only while ACTIVE.** A paused config is `false`: nothing will send, so a shopper's opt-out
  for it would be exactly the do-nothing switch this field exists to prevent.
- **It says only *that* a channel exists.** Not the number, not the WABA id, not the sealed token.
  A shopper learns the same fact the first time a message arrives. Pinned by
  `theProfileNeverLeaksTheCredential`, which serializes the real response and asserts none of the
  three appear — this is an anonymous, publicly cached read.
- One PK lookup on a table most orgs have no row in, read in `profile()` rather than joined into
  `resolveOrg` (every other public read calls that and none of them needs this).

## Tests

`StorefrontProfileIT` +2 (the three-state flag, and the leak scan). Full backend sweep: **1056 ITs
green**.
