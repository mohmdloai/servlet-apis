# Surfacing two dead portal capabilities — per-device revoke, and an unsubscribe a human can see

> Branch `146_feat/portal-surfaces`, cut from **`master`** (highest PR/issue was #145 — re-verify
> at PR time; dependabot mints numbers). Frontend pair: `frontst` stories **97**
> (`97_st_portal_devices.md`) and **98** (`98_st_unsubscribe_confirmation.md`) — **land this
> first**; both frontend branches are unbuildable against a master without it.
> **No migration.** Neither change stores anything new: the revoke writes the Redis keys the
> session store already owns, and the unsubscribe change rewrites a string.
>
> **What this is.** An audit of shipped-but-unreachable backend surface turned up three items;
> two of them need a backend change before a frontend can exist, and those two are this story.
> (The third — `?featured=true` has no browse target — is frontend-only, `frontst` story 99.)

---

## The reality this corrects

Both halves are the same shape of defect: **the capability shipped, and then nothing was ever
pointed at it.**

**1 · The shopper cannot sign out a device.** `GET /api/portal/auth/sessions` has returned the
customer's device list since the portal auth core landed, and `POST /api/portal/auth/logout-all`
has been able to end all of them. There is no verb in between. The staff plane has had
`DELETE /api/auth/sessions/{familyId}` the whole time — so a merchant who loses a phone can sign
out that phone, and their customer, on the same product, can only choose between doing nothing and
signing out of everything including the session they are standing in.

**2 · The unsubscribe link lands on raw JSON — and prefetch fires it.**
`MagicLinkService.issueUnsubscribeLink` returned `{publicBaseUrl}/api/public/unsubscribe/{token}`,
so the footer of every customer email pointed straight at the servlet. Two things follow:

- The shopper who clicks it sees `{"unsubscribed":true}` in a browser tab. No store branding, no
  confirmation, no way back.
- **`PublicUnsubscribeServlet` applies the change on `GET`.** Any mail client, security scanner, or
  link-preview bot that fetches URLs in a message body unsubscribes that customer, silently, with
  no human involved. The servlet's own Javadoc named this — *"the mail-prefetch trade-off and a
  confirm-page/`List-Unsubscribe-Post` hardening are a later refinement (see the story)"* — and
  this is that story for the confirm-page half.

## Grounding (verified against the code this story changes)

- `CustomerSessionStore.revokeFamily(familyId, orgId, customerId)` **already exists** and is already
  ownership-scoped: it returns false unless the family is a member of that customer's set
  (`sismember` on `crt:user:{org}:{customer}`) and only then deletes the family's token keys. So the
  service method below adds no new Redis semantics — it wires an existing, already-correct primitive
  to a route.
- `CustomerSessionStore.denyFamilyAccess` is the per-device access-token kill-switch
  (`crt:revoked-fam:{familyId}`, access-TTL), read by `CustomerAuthFilter` via
  `isDeviceRevoked`. Without it a revoked device keeps reading for up to the 15-minute access TTL.
- `PortalCsrf.isMutation` **already lists `DELETE`**, so the new route inherits the Origin check
  with no filter change; `X-Portal-Request` is required of every portal call regardless of verb.
- `MagicLinkService.issueOrderViewLink` already builds `{publicBaseUrl}/{locale}/{orgSlug}/…` off
  the org's `slug` + `default_locale` (`en` fallback). The unsubscribe link becomes the same shape
  by the same code — this is a precedent being followed, not a pattern being invented.
- `org.default_locale` is `NOT NULL DEFAULT 'ar'` (V52), so the fallback branch is unreachable for
  any org created after V52 and exists only for symmetry with the order-view link.

## The change

### 1 · `DELETE /api/portal/auth/sessions/{familyId}` → 204

`CustomerAuthService.revokeSession(orgId, customerId, familyId)` — the staff plane's
`AuthService#revokeSession` scoped to a customer:

```java
if (!sessionStore.revokeFamily(familyId, orgId, customerId)) {
  throw new NotFoundException("Session not found: " + familyId);
}
sessionStore.denyFamilyAccess(familyId, accessTtlSeconds());
```

Three decisions worth stating, because each is a place the obvious implementation is wrong:

- **The ownership check is the revoke.** There is no separate "does this family belong to you"
  query that could drift out of sync with the delete — `revokeFamily` refuses and reports it. A
  foreign family id is therefore a `404` that revokes nothing, which is the same answer an unknown
  id gets: the caller cannot use this endpoint to learn whether a family id exists.
- **No `token_version` bump.** That is `logoutAll`'s hammer; bumping it here would invalidate every
  *other* device too, which is precisely the opposite of what a per-device revoke means. Pinned by
  `revokeSession_leavesTokenVersionAlone`.
- **Cookies are left alone.** Revoking the device you are currently *on* is a legitimate move (it is
  the row for "this browser" in the list), and clearing the jar as a side effect would sign the
  caller out of the page they are standing on. The denylist already makes that access token dead at
  its next request. `POST /auth/logout` remains the verb that means "end this device's session".

Routing follows the servlet's existing shape: a `path.startsWith("/auth/sessions/")` branch placed
*before* the exact-match `switch` (the `/notifications/` and `/invoices/` precedent), a
`requireDelete` helper beside `requireGet`/`requirePost`, and a `UUID.fromString` guard that turns a
malformed segment into a `400` naming it rather than a 500.

### 2 · The unsubscribe link points at the storefront, not the API

`issueUnsubscribeLink` now returns `{publicBaseUrl}/{locale}/{orgSlug}/unsubscribe/{token}` — a page
that renders the store's branding, says what unsubscribing means, and **applies nothing on render**.
The shopper's button press is the `POST`.

**The servlet's `GET` is deliberately left working.** Every unsubscribe link already sitting in an
inbox carries the old URL, and `MAGIC_LINK_TTL_DAYS` defaults to 30 — breaking those to close the
prefetch hole would mean a month of dead unsubscribe links, which is a worse outcome than the hole
for the population that has already received mail. This story changes **the address we hand out,
not the addresses we honour**; the prefetch exposure ends as the old tokens expire.

`List-Unsubscribe` / `List-Unsubscribe-Post` headers remain unshipped and out of scope — that is the
*other* half of the servlet's deferred refinement, and it wants a header on the outgoing message
rather than a change to the link.

## Cost

One extra `org.findById` per customer email delivery (inside the producing business transaction),
because the link now needs the slug and locale. The order-view link in the same method already pays
exactly this, against the same already-committed row, so the notify path goes from one org read to
two rather than from none to one.

## Acceptance criteria

**AC1 — a shopper can sign out one device.** With two logins for one customer,
`DELETE /auth/sessions/{phoneFamily}` returns 204; the phone's access token is denylisted
(`isDeviceRevoked`) and its refresh no longer rotates; the laptop is untouched on both axes and the
device list drops to one row. *(`revokeSession_killsOnlyThatDevice`)*

**AC2 — a per-device revoke is not a logout-all.** After revoking, the customer's `token_version`
still validates, so sibling devices keep working. *(`revokeSession_leavesTokenVersionAlone`)*

**AC3 — an id you do not own is a 404 that revokes nothing.** Another customer's family id throws
`NotFoundException`, that customer's device is *not* denylisted, and their refresh still rotates.
An unknown random id is the same 404. *(`revokeSession_anotherCustomersFamily_is404AndRevokesNothing`,
`revokeSession_unknownFamily_is404`)*

**AC4 — a malformed family id is a 400, not a 500.** `DELETE /auth/sessions/not-a-uuid` →
`ValidationException` naming the segment.

**AC5 — the emailed unsubscribe link is a page, not the API.** A minted link does not contain
`/api/public/unsubscribe/` and starts with `{publicBaseUrl}/{default_locale}/{orgSlug}/unsubscribe/`.
*(`unsubscribeLink_targetsTheStorefrontPage_notTheApi`)*

**AC6 — the token still resolves and still suppresses email.** The existing round-trip
(mint → `resolveUnsubscribe` → `unsubscribeCustomerEmail` → the customer's next notification has zero
email deliveries and one surviving in-app leg) is unchanged; the token is still the last path
segment. *(`unsubscribeAppliesAndSuppressesEmail`, unchanged)*

## Tests

`api` module, both existing IT classes — no new class, because both changes are new behaviour on
machinery these ITs already stand up:

- `PortalAuthIT` — four new cases (AC1–AC3), plus a `familyOf(SessionResult)` helper that reads the
  `fam` claim, replacing the inline parse the two older session tests each spelled out.
- `NotificationPreferenceIT` — one new case (AC5) beside the untouched round-trip (AC6).

Run: `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock mvn -o test -pl api
-Dtest='PortalAuthIT,NotificationPreferenceIT'` → **29/29 green**.
