# Rescue a stuck signup — see that an owner never verified, and resend the link

> Branch `132_feat/platform-resend-verification`, cut from **`master`** (highest PR/issue is #131 —
> re-verify at implementation time; dependabot mints PRs). Frontend pair:
> `frontst` story 79 (`79_st_platform_resend_verification.md`, branch `77_feat/…`) — **land this
> backend first**.
> **No migration.** Every column this needs already exists; what is missing is that three DTOs
> refuse to carry them and one endpoint does not exist.
>
> Named in the platform-console epic's "Open, and deliberately so" list. Slice 1.5 refused to expose
> Reactivate on a PENDING org, for the right reason. This is the action it refused to fake.

---

## The reality this corrects

A self-serve signup lands as an **unverified `app_user`** plus an org born `active = false`. The org
reads PENDING (slice 1.5's `OrgStatus`), and the causal chain out of that state is exact:
`AccountService.verifyEmail` stamps `email_verified_at` and then calls
`OrgRepository.activateRegistrationPendingOrgs(userId)`. **Verifying the email is literally what
activates the tenant.** So a PENDING org means one thing, always: its owner has not clicked the link.

Slice 1.5 already says this on screen, and stops there — `OrgLifecycle` renders *"Reactivate applies
to suspended orgs. This one is waiting on its owner's email."* That sentence is correct, and it is
the whole of the console's answer to a real support call.

`POST /api/auth/resend-verification` exists, and is the wrong tool three times over:

- It is **anonymous and enumeration-safe by construction** — a uniform `200 {}` whatever happened,
  every failure swallowed (`AccountService.resendVerification` catches `RuntimeException` and logs).
  An operator who calls it learns nothing about whether anything was sent. That silence is correct
  for the public plane and is exactly what a console must not do.
- It is keyed on the **email**, and the console never shows the operator an email to type. See below.
- It is bucketed per IP at `AUTH_FORGOT_LIMIT` — a support desk working a queue of stuck signups
  would throttle itself against a limit designed for an attacker.

### Three gaps, not one

1. **The console cannot see the state.** Neither `AdminUserResponse` (list) nor
   `AdminUserDetailResponse` (detail) carries `email_verified_at`. `app_user` has had the column
   since V49 and no platform surface has ever read it back. Today there is no screen anywhere that
   can tell an operator "this account never verified".
2. **The console cannot name the owner.** `AdminOrgDetailResponse` is
   `{org, status, suspended_at, suspended_reason, health{memberCount, …}}` — four counts and no
   identity. An operator on a PENDING org's page knows *that* it is waiting on its owner's email and
   cannot find out *whose*. `memberCount: 1` is as close as it gets.
3. **There is no action**, on either plane, that an authenticated operator can take.

## The trap: the console can permanently brick a tenant, and does it while trying to help

`activateRegistrationPendingOrgs(ownerId)` activates an org only when all three hold:

```
ORG.ACTIVE = false                     -- PENDING or SUSPENDED…
AND ORG.SUSPENDED_AT IS NULL           -- …narrowed to PENDING
AND EXISTS (ownerId holds OWNER here)
AND NOT EXISTS (any other member)      -- ← this one
```

That last clause means: **grant any second role on a PENDING org and verification stops activating
it, permanently.** The owner clicks the link, `email_verified_at` gets stamped, the `UPDATE` matches
zero rows, and the tenant sits PENDING forever with no path out — Reactivate is refused on a pending
org (correctly), and nothing else flips `active` back.

Two facts make it worse rather than theoretical:

- **The only reachable way in is the console's own `POST /api/admin/users/{id}/org-roles`.** The
  org-plane roster write (`POST /api/orgs/{orgId}/members`) requires OWNER, and this org's owner is
  403-blocked at login until they verify. So the sole path to a second member is an ADMIN — most
  plausibly one *trying to help a stuck signup* by adding a colleague. The failure mode is that the
  rescue attempt is what makes the rescue impossible.
- **The purge never cleans it up.** `unverified-account-purge` cascades the sole-member org only;
  "multi-member orgs are never touched". So the bricked tenant is permanent by two independent rules.

**Refuse the grant.** `POST /api/admin/users/{id}/org-roles` → **409** when the target org is
PENDING, naming the cause ("this tenant is waiting on its owner's email verification"). This is
strictly better than today even ignoring the trap: a member added to a PENDING org cannot use it
anyway — `requireOrgAccess` 403s them with *"Org suspended"*, which is the pending/suspended
conflation the epic already has open one layer down. Refusing the grant makes that conflation
unreachable by construction rather than merely unreached.

**Do not loosen the activation predicate instead.** The `NOT EXISTS` clause is what stops an
unrelated inactive org from activating just because its owner happened to verify; replacing it needs
a column recording "this org was born at this user's registration", which does not exist and is not
worth minting for this. Close the door, do not widen the room.

## The change

### 1. Let the console see it — `email_verified` on both user DTOs

`AdminUserResponse` and `AdminUserDetailResponse` each gain **two** fields:

```
"email_verified": false,                      // always present
"email_verified_at": "2026-07-20T09:14:00Z"   // omitted when null
```

**Both, deliberately, and this is not redundancy.** Jackson omits nulls, so a bare
`email_verified_at` would make *absence* mean "never verified" — a client that forgets the key, or a
response that drops it for any other reason, reads the alarming state as nothing-to-say. Everywhere
else in this codebase a missing key means "no news" (`degraded[]`, empty search groups,
`suspended_reason`). Inverting that polarity for the one field whose absence is the incident is how
a console lies quietly. The boolean is always present and carries the fact; the timestamp is the
detail.

### 2. Let the console name the owner — `owners` on the org detail

`AdminOrgDetailResponse` gains `owners: [{id, email, display_name?, active, email_verified}]` — the
members holding `OWNER`, not the whole roster. `health.memberCount` already says how many people are
in there; what the detail page lacks is **who to act on**, and on every path that reaches this page
the answer is an owner. Plural because `user_org_role` permits several and a provisioned org can
gain more.

This needs `OrgMember` to carry `emailVerifiedAt` (it currently carries
`userId, email, displayName, roles, active, createdAt`). Add it to the projection **once** — the
org-plane roster `GET /api/orgs/{orgId}/members` gets it too, and should: that read is MANAGER-gated
and already exposes each member's email and active flag, so "has this colleague verified" is neither
new information nor a new audience. One definition, two callers — the epic's recurring cure.

### 3. The action — `POST /api/admin/users/{id}/resend-verification`

`requireAdmin` (a mutation: it sends mail on the platform's behalf and re-arms a credential link).
Body is optional:

```
{ "org_id": "…" }     // optional — the tenant this rescue is for
```

Outcomes, all cause-naming, because **there is nothing to protect here**. The caller is an
authenticated ADMIN who was just shown the address by the very page they clicked from; enumeration
is not a threat model when the console already displays the directory.

| case | response |
|---|---|
| unknown user id | **404** |
| user already verified | **409** "this account is already verified" |
| user inactive | **409** "this account is disabled" — a disabled account must not be handed a link that logs it in |
| `org_id` present, user holds no OWNER role there | **400** naming the mismatch |
| sent | **200** `{email, expires_at}` |
| the send threw | **502**, naming that the link was minted but not delivered |

**The send failure is the interesting one, and it must not be swallowed.** The anonymous path
swallows because a 5xx on a real address is an enumeration oracle. That reason does not exist here,
and the substitute reason — "return 200 anyway, it's simpler" — reports success for an email that
never left. An operator who is told "sent" and whose caller never receives it will spend the next
hour on the wrong hypothesis. Report the failure; the operator retries.

Behaviour otherwise mirrors the public path exactly, by calling the same machinery rather than a
second copy: `CredentialTokenService.invalidateActive(userId, EMAIL_VERIFY, now)` then
`mintAutonomous` then `mailer.sendVerifyEmail(email, tokenService.verifyUrl(raw))`. Prior live links
are superseded — only the latest redeems — which is the existing guarantee and must survive.

**No rate-limit bucket, and that is a decision.** `requireAdmin` + an audit row per call is the
control; a per-IP bucket sized for anonymous abuse would throttle a support desk clearing a backlog
and protect nothing an ADMIN could not do anyway. Say so in the handler's Javadoc so the next
contributor does not add one by reflex, and so the omission is visibly deliberate rather than
forgotten.

#### The audit row, and what `org_id` means here

Audit `EMAIL_VERIFY_RESEND` with `Target.USER` / `targetId = userId` and
`detail = {email, org_id?}`. V76 made the org a required parameter of
`PlatformAuditService.record`, so this slice is the first *new* audited action that has to answer the
question that parameter asks — answer it properly:

- **`org_id` absent from the body** (the operator acted from `/admin/users/{id}`, with no tenant in
  mind) → pass `null`. A resend targets a person, and a person is not a tenant event.
- **`org_id` present** (the operator acted from that tenant's page, which is the path this slice
  exists to build) → pass it, after validating the user actually owns that org. The rescue then
  appears on **that tenant's timeline**, which is precisely the per-tenant question slice 4 built
  the column for: *who got this tenant unstuck, and when*.

The validation is what keeps the parameter honest — without it the body could attribute the action
to any org at all, and an audit column you can point anywhere is worse than a null one.

### 4. Close the trap — 409 on granting into a PENDING org

`POST /api/admin/users/{id}/org-roles` refuses when the target org's `OrgStatus` is `PENDING`.
Use the enum, never a re-derived boolean (slice 1.5's whole point). SUSPENDED orgs still accept
grants — an admin staffing a suspended tenant ahead of reactivation is legitimate and nothing about
it is unrecoverable.

## Tests

`PlatformResendVerificationIT` (api module, TestContainers):

- `resendOnUnverifiedUser_sendsAndReports200` — asserts a *fresh* `EMAIL_VERIFY` token exists
  afterwards and the response names the address.
- `resendSupersedesPriorToken` — the prior link no longer redeems; the new one does.
- **`rescuedSignupActuallyActivatesTheOrg`** — the end-to-end claim of the slice: register (org
  PENDING) → admin resend → redeem the new link → **the org is ACTIVE**. If this passes without the
  trap fix, the trap fix is untested; see the next one.
- **`grantIntoPendingOrg_is409`**, and beside it
  **`grantIntoPendingOrg_wouldHaveBrickedActivation`** — the regression proof: with the guard
  bypassed at the service level, verification leaves the org PENDING. Write this one even though it
  tests a state the API now refuses to reach, because the guard is the only thing standing between
  the two and a future refactor will not know that.
- `alreadyVerified_is409` · `disabledUser_is409` · `unknownUser_is404`.
- `orgIdNotOwnedByUser_is400`.
- `orgIdPresent_landsOnThatTenantsTimeline` — read `GET /api/admin/orgs/{orgId}/timeline` and assert
  the entry is there. `orgIdAbsent_isNotOnAnyTimeline` — the null half; a resend with no tenant named
  must not appear under some org the user happens to belong to.
- `sendFailure_is502_andIsNotReportedAsSent` — with a failing `EmailSender`.
- `support_is403` · `orgOwner_is403` · `anonymous_is401` — a read tier cannot send mail.
- `emailVerifiedIsPresentAndFalse_onUnverifiedUser` — pins the always-present boolean on both the
  list and detail DTOs. The polarity argument above is only as good as this assertion.
- `orgDetailNamesItsOwners` — including one PENDING org where the owner is unverified, which is the
  page the frontend is built against.

`AccountServiceTest` / existing IT coverage of the anonymous path must stay green **unchanged** —
this slice adds a caller, it does not alter the enumeration-safe endpoint.

## Definition of done

- [ ] `mvn -o test` green; full `*IT` battery green. Export **both**
      `DOCKER_HOST=unix:///Users/ninja/.colima/default/docker.sock` and
      `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. **Do not use
      `TESTCONTAINERS_RYUK_DISABLED=true`** — it leaks containers and is not needed.
- [ ] `mvn spotless:apply` clean.
- [ ] **No migration**, stated as a checked observation — if one turns out to be needed, that is a
      finding worth reporting before writing it.
- [ ] Every new/changed audit write passes an explicit org or an explicit `null`; no overload added.
- [ ] `CLAUDE.md` §"Platform admin" documents the endpoint, the two new DTO fields, `owners` on the
      org detail, and the PENDING-grant 409 (gitignored — edit it anyway).
- [ ] `perfdb` and the dev `inventorydb` both intact. **Never `docker compose down -v`.**
