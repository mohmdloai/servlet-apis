# Slice: Support reach — the operator's feed, the auto-close, the suspended door

> Slice 2 of the support-tickets epic (`frontst/docs/support-tickets-epic.md`), after
> [`support_tickets.md`](./support_tickets.md) (V97). **No migration.** Branch
> `{PR#}_feat/support-ticket-reach` off `master`. Frontend pair:
> `frontst/stories/158_st_support_reach.md`. Merge this first.
>
> **Posture.** Slice 1 wrote the operator's in-app notification rows and could not read them; it
> let a resolved ticket sit resolved forever; and it kept the door shut on the one tenant most
> likely to need it. This slice closes those three gaps and nothing else. Each is small; each is
> the kind of gap the console epic learned to promote — *a place the system can see a problem and
> not act on it.*


> **Built 2026-09-13** on `188_feat/support-ticket-reach` (off `master` d621fb0), no migration:
> `NotificationAdminHandler` at `/api/admin/notifications` (`AdminServlet` entry `notifications`;
> `PlatformNotificationResponse` = the org row + `org {id,name}`) over
> `NotificationService.getUserFeed / countUserFeed / markOwnRead / markOwnDismissed` and
> `NotificationRepository.findUserInAppFeed / countUserInAppFeed / markUserInAppRead /
> markUserInAppDismissed` — the org feed's queries with the org predicate dropped (`orgScope`),
> so the two planes cannot drift; `SupportTicketAutoCloseJob` (`JOB_SUPPORT_TICKET_AUTO_CLOSE`,
> `SUPPORT_AUTO_CLOSE_INTERVAL` / `_DAYS` / `_BATCH_LIMIT`, the activator branch, `scheduleRecurrently`)
> over `SupportTicketService.autoClose(now, days, batch)` with
> `SupportTicketRepository.findAutoCloseCandidates` + `lockAutoCloseCandidate` (`FOR UPDATE SKIP
> LOCKED`, one txn per ticket, `ticketClock()` for the stamp); `AuthzHelper.requireOrgAccessThroughSuspension`
> (the shared private body with `enforceSuspension`; `SupportTicketHandler` its only caller) and
> `OrgSuspendedException` (`kind = ORG_SUSPENDED`, mapped in `ApiErrors.body`). Tests:
> `OrgSuspensionEnforcementTest` (+5: the kind, the outsider's absence of it, the variant's admit /
> membership / rank / read-only), `SupportTicketHandlerTest` (+2: the door at the wire, the feed's
> gates + shapes), `AppConfigJobCronsTest`, `NotificationAdminIT`, `SupportTicketAutoCloseIT`,
> `SuspendedDoorIT` (over a shared `SupportReachItBase`) — 34/34 with `SupportTicketIT`; the
> module batteries green; `spotless:apply`; `CLAUDE.md` gained the paragraph. Two notes: the
> "job id in the overview's health" criterion is asserted at its source (`resolveJobCrons`, now
> package-private) rather than through JobRunr's tables, which hold no row for a job that never
> ran; the suspended-door IT runs at the service seam (no HTTP layer in the ITs), so the 403 +
> kind is covered by the gate's unit test and the handler test's mocked request.

---

## Goal

An operator sees the platform's notifications in the console and reads them from their phone; a
`RESOLVED` ticket the merchant never answered becomes `CLOSED` after seven days with a thread row
that says so; a member of a **suspended** org can open and read support tickets while every other
route stays locked, and the org app can tell suspension apart from no-access.

---

## What exists, what is missing

- **The rows exist, the read does not.** Slice 1 produces `SUPPORT_TICKET_OPENED` /
  `_UPDATED` for every active `ADMIN`/`SUPPORT` user with `org_id` = the ticket's org; `channelsFor
  (USER)` gives each an `IN_APP` delivery and a `PUSH` one where subscribed. `NotificationHandler`
  is mounted at `/api/orgs/{orgId}/notifications` behind `requireOrgAccess(VIEWER)` — an operator
  with no membership in the org gets a 403, and the console has no bell. The feed query
  (`NotificationRepository.findInAppFeed`) is keyed on `recipient_user_id` with
  `idx_notification_recipient_user (recipient_user_id, created_at DESC)` — a user-scoped read
  across orgs, the `/api/me` shape, not a cross-tenant one.
- **Background work is a recurring poller over a state column**, never an enqueue
  (`NotificationService`'s Javadoc says why). Jobs are registered in `AppConfig`: a public id
  constant, a cron with an env override, the activator branch, `scheduleRecurrently`; the overview
  reads job health by id automatically.
- **`requireOrgAccess` checks membership first, then the `OrgStatusGate`**, and throws
  `AuthorizationException("Org suspended")` for every member of a suspended org — deliberately
  after membership so an outsider cannot probe which orgs are suspended. There is no
  machine-readable discriminator on that 403; the org app cannot tell it from "no access".
  A platform ADMIN bypasses the gate (to enter a suspended org and fix it).

---

## Why this shape

### The operator's feed is a user-scoped read on the platform plane

`GET /api/admin/notifications` reads the caller's own rows by `recipient_user_id` — every org's,
because an operator's notifications concern many tenants. It is not a fifth cross-org sibling:
the scope is the user, the way `/api/me` and `/api/orgs` already read across a user's
memberships, and nothing in it can return another user's row. The response is the org feed's
shape exactly (`PageResponse<NotificationView>` with `?unread=true`) plus `org {id, name}` per
row, because the console has no current org to imply it. `read` and `dismiss` are the same
own-row mutations the org handler already performs; the handler is a thin platform twin that
delegates to the same service methods. Gate: `requirePlatformRead` for the GET,
`requireSupportDesk` for the two writes.

### Auto-close is a poller, and it writes the thread

`SupportTicketAutoCloseJob` (`support-ticket-auto-close`) runs `SupportTicketService.autoClose
(now, days, batch)`: `status = 'RESOLVED' AND resolved_at < now − days`, `FOR UPDATE SKIP LOCKED`,
batch of 100, each in its own transaction → `close(null, AUTO, now)` + a `STATUS` row with
`author_id NULL`, `status_to = 'CLOSED'`. **No notification**: a merchant who did not answer a
resolution in seven days is not waiting for news of it, and a push saying "we closed your ticket"
reads as a door shutting. `SUPPORT_AUTO_CLOSE_DAYS` (default `7`) and
`SUPPORT_AUTO_CLOSE_INTERVAL` (default hourly, `0 15 * * * *`) via `AppConfig.resolveJobCrons`.
`JOB_SUPPORT_TICKET_AUTO_CLOSE` is public so the overview names it.

### The suspended door, named once

Support is the one route a suspended tenant must reach. `AuthzHelper.requireOrgAccessThroughSuspension
(req, orgId, minRole)` is `requireOrgAccess` with the `OrgStatusGate` step skipped — membership,
read-only impersonation and rank all still apply. **Exactly one caller: `SupportTicketHandler`.**
Its Javadoc names the exception and the reason, so the next person who greps for the gate finds
the rule beside the hole. (A `PENDING` org's members are unverified owners who cannot log in; the
variant admits them too, harmlessly — nothing reaches it.)

And so the org app can *lead* a suspended member to that door, the 403 becomes machine-readable:
`OrgSuspendedException extends AuthorizationException` with `KIND = "ORG_SUSPENDED"`, thrown from
the same line that threw the generic message. It is only ever thrown **after** the membership
check, so the enumeration property holds: an outsider still gets the generic "No access".

---

## Application & wire

### Endpoints — platform plane

| method · path | gate | → |
|---|---|---|
| `GET /api/admin/notifications` | `requirePlatformRead` | `?unread=true&page&size` → `PageResponse<NotificationView + org{id,name}>`, own rows only |
| `POST /api/admin/notifications/{id}/read` | `requireSupportDesk` | `204` · `404` for a row that is not the caller's |
| `POST /api/admin/notifications/{id}/dismiss` | `requireSupportDesk` | `204` · `404` |

`AdminServlet` entry `notifications` → `NotificationAdminHandler`.

### The job

```
SupportTicketService.autoClose(OffsetDateTime now, int days, int batch) → int closed
```

`AppConfig`: `JOB_SUPPORT_TICKET_AUTO_CLOSE = "support-ticket-auto-close"`, cron + env, the
activator branch, `scheduleRecurrently`. `PlatformOverviewService.JobConfig` picks it up by id.

### The door

`AuthzHelper.requireOrgAccessThroughSuspension(...)` (one caller); `OrgSuspendedException`
(`kind = "ORG_SUSPENDED"` via `ApiError.ofKind`); `ApiErrors.body` maps it. Every other org
route is unchanged and now 403s suspended members with the `kind`.

---

## Authorization

The feed and its writes are the caller's own rows on the platform plane; nothing here widens what
any tier can see. The door widens what a **member** of a suspended org can do to exactly one
resource, at the same rank rules as before.

---

## Out (deferred)

- Priority, internal notes, `#1042` in search, first-response metrics — slice 3.
- A notification when the auto-close fires (decided against, above).
- Letting a suspended org's members read anything else (orders, money) — that is what suspension
  means.

---

## Tests

`NotificationAdminIT`: two desk users each see only their own rows, across two orgs, with `org`
attached; `?unread=true` totals; `read`/`dismiss` on another user's row → 404; a tenant OWNER →
403.

`SupportTicketAutoCloseIT`: a `RESOLVED` ticket at `resolved_at = now − 8d` → `CLOSED`, `closed_reason
= AUTO`, `closed_by NULL`, a `STATUS` row with `author_id NULL`; one at `now − 6d` untouched; an
`OPEN` one untouched; no notification rows written; the job id is present in the overview's job
health.

`SuspendedDoorIT`: suspend an org; its STAFF member can `POST /support-tickets`, `GET` the thread
and post a message; the same member's `GET /orders` is `403` with `kind = ORG_SUSPENDED`; a
non-member's `GET /orders` is `403` with no `kind`; the desk sees the ticket with the tenant's
`suspended` status. `AuthzHelperTest`: the variant skips exactly the gate step.

## Definition of done

Handler + the two gate pieces + the job + the exception + the suites above green;
`spotless:apply`; `CLAUDE.md`'s platform paragraph gains the feed and the door; story on the
branch; frontend pair 158 ships after this merges.
