# Slice: Support tickets — a merchant opens one, the desk works it, both are told

> Slice 1 of the support-tickets epic (`frontst/docs/support-tickets-epic.md` — read it first; the
> state machine, the locked rule and the eight owner decisions live there and are not repeated).
> **Branch `{PR#}_feat/support-tickets` off `master`, migration V97** (V96 is web push; re-verify
> the counter at branch time). Frontend pairs: `frontst/stories/156_st_support_tickets.md` (the
> org app) and `157_st_support_desk.md` (the console). Merge this first; against the old API both
> frontends 404 on their loaders and show the designed unavailable state, not a blank page.
>
> **Built 2026-09-13** on `187_feat/support-tickets`: V97 + `SupportTicket` (the transition table
> as methods, every cell unit-tested) + the org-scoped repository + `PlatformTicketRepository` (the
> fifth enumerated sibling) + `TicketDeskPredicates` shared with `PlatformStatsRepository.ticketCounts()`
> + `SupportTicketService` / `SupportDeskService` + `SupportTicketHandler` (`support-tickets`) /
> `TicketDeskAdminHandler` (`tickets`) + `AuthzHelper.requireSupportDesk` + `activeDeskUserIds()` +
> four `NotificationType`s with ar/en templates + the presigner's first content-type allowlist +
> the overview's `support` section. Green: `SupportTicketMachineTest` 11, `SupportTicketHandlerTest`
> 8, `SupportTicketIT` 8, `NotificationTemplatesTest` 34, `WhatsAppTemplatesTest` 19, the service
> suite 296, and the neighbours `PlatformOverviewIT` 14 · `PlatformQueuesIT` 25 ·
> `PlatformTenantStatesIT` 10 · `PlatformOrgTimelineIT` 25 · `NotificationDeliveryIT` 6 ·
> `LowStockNotificationIT` 11. Measurement in `tools/seed/results/support_desk_187.txt` (scratch
> DB, 10,000 tickets): the OPEN queue is an index scan at 0.26 ms; the CLOSED tab is the Seq Scan
> the story predicted (3.7 ms) — no third index. One deviation from the spec below: the two
> conflict kinds ride `InvalidTicketTransitionException` (`TICKET_CLOSED` / `TICKET_TRANSITION`)
> and `TicketCapException` (`TICKET_CAP`), both mapped once in `ApiErrors`.
>
> **Posture.** This is the first surface where a tenant *writes to the platform*. Everything it
> writes is org-scoped and role-gated on the way in; everything the desk reads across tenants
> goes through one whitelisted sibling repository, the way the console epic enumerated the other
> four. The desk's replies are the first thing the `SUPPORT` tier may write, through a gate named
> for it. The rule that outranks the rest: **a ticket the operator does not see is worse than no
> ticket form** — so the desk-side notification rows are written in the ticket's own transaction,
> and the desk's counts, tab badges and inbox are one predicate.

---

## Goal

`POST /api/orgs/{orgId}/support-tickets` creates a numbered ticket with a category, a subject, a
first message, optional screenshots and the merchant's *"I can't sell right now"* flag; the thread
grows by messages from either side; the status is always one of `OPEN | AWAITING_MERCHANT |
RESOLVED | CLOSED` with one owner; the desk lists across tenants by status, reads a thread with
the tenant beside it, replies, resolves, closes; every move notifies the other side inside the
same transaction; an operator's verbs land on the tenant's timeline.

Done means: a cashier at a counter with a dead printer can send a screenshot and a sentence
without leaving the app; the owner's phone buzzes before the cashier has put it down; the reply
shows up in the cashier's app; nobody can ever ask "who has this ticket?".

---

## What exists, what is missing

- **Nothing resembles a ticket.** No table, service, route or story (grep `ticket|support|helpdesk|
  feedback|contact`: the receipt is called a ticket in the POS stories; `SystemRole.SUPPORT` is the
  read-only operator tier; `org.contact_email` is the tenant's letterhead). `InvoiceService` and
  `CreditNoteService` emit *"… contact support."* with no channel behind the words.
- **The closest structural precedent is `listing_comment`** (V61): a customer message, one staff
  reply, a `TEXT + CHECK` status, `reply()` that publishes and notifies in one transaction,
  `DISMISSED` as a terminal state, an opaque 404 for a foreign id. This slice is that shape with
  a real thread, two planes and a state machine.
- **Notifications:** `NotificationService.notify(txDsl, orgId, recipient, type, payload,
  sourceType, sourceId, linkTarget)` and `notifyOrgStaff(...)`; a `USER` recipient gets
  `IN_APP` + `PUSH` (when a subscription exists) and **no email leg**. A new type is a constant, a
  template case (ar/en, exhaustive switch) and a `WhatsAppTemplates -> null` arm; no migration.
  `notification.org_id` is `NOT NULL`, and a platform user is an `app_user`, so
  `NotificationRecipient.user(adminId)` with the ticket's `org_id` is a legal row today. The
  console cannot *read* it until slice 2 — stated, not hidden.
- **Attachments:** `ObjectStorage` is a presigner (`presignPut`, `presignGet`), never bytes. The
  payment-proof flow (`shopper_payment_proof_claim.md`) is the model: a key family with a
  write-side prefix guard **and** a read-side re-check before minting a GET (`presignProof`). There
  is no content-type allowlist anywhere yet, and no size cap is possible at presign.
- **Platform plane:** `AdminServlet` maps resources to `*AdminHandler`s; reads pass
  `requirePlatformRead` (ADMIN or SUPPORT), mutations `requireAdmin`. Cross-org reads exist only
  as the four named siblings (`PlatformStatsRepository` counts-only, `PlatformQueueRepository`
  rows-whitelisted, `PlatformSearchRepository`, `PlatformFunnelRepository`); counts and rows share
  a package-private predicates class (`PlatformQueuePredicates`). `PlatformAuditService.recordInTx`
  takes `orgId` as a required positional argument; the org timeline is `platform_audit ∪
  impersonation_event` by `org_id`, so an audited desk verb lands on it for free.
- **Authorization:** `AuthzHelper.requireOrgAccess(req, orgId, minRole)` enforces membership,
  suspension (the `OrgStatusGate`), read-only impersonation and rank; `hasManagerAuthority(sc,
  orgId)` is the pure predicate the cash shift hands to its service for *own vs. manager*.
- **Text:** `Text.normalizeText` at the DTO → domain boundary for every human string.

---

## Why this shape

### A message is the transition

The merchant has one verb. *Send* on an `OPEN`, `AWAITING_MERCHANT` or `RESOLVED` ticket puts it
in `OPEN` — a reply, an answer, or a reopen are the same act from their side, and none of them
needs a second button or a status picker. The desk's *Send* puts it in `AWAITING_MERCHANT`;
*Send & resolve* and *Resolve* put it in `RESOLVED`; a desk message on a `RESOLVED` ticket
**stays resolved** (a footnote is not a reopen). *Close* from either side is terminal, and a
`CLOSED` ticket refuses every write with `409 TICKET_CLOSED` — the merchant opens a new one and
the client pre-fills *"follows #1042"*. This is `SupportTicket.postMessage(side, actor, body,
resolve, now)` plus `resolve` and `close`, each a `requireStatus(...)` guard like `SalesOrder`;
`InvalidTicketTransitionException extends ConflictException`.

Every transition writes a `STATUS` row into the thread (`kind = 'STATUS'`, `status_to`, the actor
or `NULL`), so the merchant reads *"Marked resolved · 2 hours ago"* between two messages and no
second history table is ever joined. `status_since` is stamped on each transition — the desk
queue's sort key — and `last_activity_at` on every write — the ledgers' sort key.

### The desk is a sibling, not a method

The inbox is a cross-org read, so it is the console epic's **fifth enumerated sibling**:
`PlatformTicketRepository` returns `DeskTicketRow(ticket fields, org {id, name, slug, status},
openedBy {id, displayName})` — never an email, never a body. `PlatformStatsRepository.ticketCounts()`
returns `{open, awaitingMerchant, resolved, closed, blockingOpen}`; **both consume
`TicketDeskPredicates`** (package-private, `repository`), so the overview tile, the tab badge and
the list cannot disagree. The single-ticket desk read (`GET /api/admin/tickets/{id}`) is org-scoped
by the ticket's own `org_id` — an ordinary org-scoped read on the platform plane, like the
timeline — and carries the opener's email because a reply may need it.

### A named gate for the desk

`requireAdmin` guards tenant mutations; a ticket reply is not one. `AuthzHelper.requireSupportDesk
(req)` admits `ADMIN` or `SUPPORT` and is used by every desk write. Its Javadoc says the one
sentence that matters: *the desk is the only place the SUPPORT tier writes, and what it writes
changes no tenant data.* Read-only impersonation does not apply (there is no org context on this
plane). Desk writes are audited: `SUPPORT_TICKET_REPLIED | SUPPORT_TICKET_RESOLVED |
SUPPORT_TICKET_CLOSED`, target type `TICKET`, `org_id` the ticket's — on the tenant's timeline
without a migration. Merchant actions are not audited: `platform_audit` is what operators did.

### Notifications in the ticket's transaction

`SUPPORT_TICKET_OPENED` and `SUPPORT_TICKET_UPDATED` (merchant replied / reopened / closed) go to
`UserRepository.activeDeskUserIds()` — active users holding `ADMIN` or `SUPPORT`, a sibling of
`activeAdminIdsForUpdate()` without the lock — each as `NotificationRecipient.user(id)` with
`org_id` = the ticket's org, `source_type = "support_ticket"`, `source_id = ticketId`. Push reaches
their phones today; the in-app rows wait for slice 2's feed. `SUPPORT_TICKET_REPLIED` and
`SUPPORT_TICKET_RESOLVED` go to the **merchant participants**: distinct `author_id` over the
ticket's `MERCHANT`-side `MESSAGE` rows that are still active members of the org (`opened_by`
is always one of them); if that set is empty (the opener left), the org's `OWNER`s. All four are
produced with the caller's `txDsl` — a rolled-back reply leaves no notification, and a committed
one cannot fail to enqueue. Templates: subject-style titles (*"Support replied on #1042"*),
one-line bodies with the ticket subject, CTA *View ticket*; ar/en both.

### Attachments are keys with a guard on both ends

`POST …/support-tickets/attachments/presign` (org plane) and `POST /api/admin/tickets/{id}/
attachments/presign` (desk, under the *ticket's* org) mint a PUT for
`ObjectStorage.newSupportAttachmentKey(orgId, filename)` → `{orgId}/support/{uuid}-{name}`. The
presigner gains its first **content-type allowlist** — `image/png`, `image/jpeg`, `image/webp`,
else `400` — because a ticket screenshot is the first upload a non-staff actor (the desk) and a
staff actor share a prefix on. `attach` refuses a key not under `supportKeyPrefix(orgId)`; the
read path re-checks the stored key against the same prefix before `presignGet`, and answers
`null` (the client shows *"couldn't load"*) rather than minting a credential for a bad key. ≤ 3
attachments per message, enforced in the service; `file_name` and `content_type` are stored so a
list can show a thumbnail count without signing anything.

### The cap

At most **10 not-`CLOSED`** tickets per org; the eleventh `POST` is `409` with `kind =
"TICKET_CAP"`. Counted inside the create transaction with the org's rows locked
(`SELECT … FOR UPDATE` on the org row — the cash shift's pattern for one-per-org), so two
phones cannot race past it. Not a rate limit: authenticated staff of an active org are not the
public plane's spam vector, and the `RateLimitFilter` is not mapped on `/api/orgs/*`.

---

## Data model — V97

The SQL is in the epic doc (§"Data model — V97") and is the migration verbatim: `support_ticket`
(+ `support_ticket_number_seq`), `support_ticket_message`, `support_ticket_attachment`, with the
two indexes `ix_support_ticket_org_activity (org_id, last_activity_at DESC)` and
`ix_support_ticket_desk (status, blocking DESC, status_since ASC)`. Header comment names this
story; `COMMENT ON TABLE` on all three.

**Measurement owed before merge:** `perfdb` has no support rows. Seed a scratch DB with 200 orgs ×
~50 tickets (statuses in a 3 : 5 : 4 : 40 ratio — most tickets end closed) and record the plan for
the desk's `WHERE status = ? ORDER BY blocking DESC, status_since ASC LIMIT 20 OFFSET n` and for
`ticketCounts()` in the migration header, the V76 way. Expect the planner to *decline*
`ix_support_ticket_desk` for the `CLOSED` tab (the bulk of the rows) and use it for `OPEN` — record
both; drop the index only if it refuses every shape.

---

## Application & wire

### Domain

```
SupportTicket.open(orgId, actor, category, subject, blocking, ref, now)        → OPEN, status_since = now
SupportTicket.postMessage(side, actor, resolve, now)  MERCHANT: OPEN|AWAITING|RESOLVED → OPEN (RESOLVED clears resolved_*)
                                                      SUPPORT : OPEN|AWAITING → AWAITING (or RESOLVED when resolve)
                                                                RESOLVED → RESOLVED; first_response_at once
SupportTicket.resolve(actor, now)                     OPEN|AWAITING → RESOLVED
SupportTicket.close(actor, reason, now)               OPEN|AWAITING|RESOLVED → CLOSED
                                                      CLOSED → InvalidTicketTransitionException("TICKET_CLOSED")
```

`TicketStatus`, `TicketCategory`, `TicketSide`, `MessageKind`, `CloseReason` are Java enums; the
columns stay open text. Every mutator stamps `updated_at` and `last_activity_at`; transitions stamp
`status_since` and return the `STATUS` row to persist.

### Service — `SupportTicketService` (org plane)

```
TicketView   open(orgId, actor, isManager, OpenCommand{category, subject, body, blocking, ref?, attachments[]})
                                                                        // 409 TICKET_CAP · 400 on shape
TicketPage   list(orgId, actor, isManager, TicketListFilter{status?}, page, size)
                                                                        // STAFF: opened_by = actor; MANAGER+: all
TicketView   get(orgId, actor, isManager, ticketId)                     // opaque 404 for a foreign or unowned id
TicketView   post(orgId, actor, isManager, ticketId, body, attachments[]) // → OPEN; notifies the desk
TicketView   close(orgId, actor, isManager, ticketId)                   // reason MERCHANT; notifies the desk
Presign      presignAttachment(orgId, filename, contentType)            // allowlist; 400 otherwise
```

`TicketView` = ticket + `messages[]` (kind ≠ `NOTE`) + per-message `attachments[]` with a fresh
presigned `url` each read. `TicketPage` rows carry `attachment_count` and `last_message_preview`
(first 120 chars of the last `MESSAGE` body, normalized) — never a signed URL in a list.

### Service — `SupportDeskService` (`service/platform`)

```
DeskPage     list(DeskFilter{status?, orgId?}, page, size)      // sibling read; OPEN sorts blocking DESC, status_since ASC; the rest last_activity_at DESC
DeskCounts   counts(UUID orgId?)                                 // {open, awaiting_merchant, resolved, closed, blocking_open}
DeskView     get(ticketId)                                       // org summary + opener {name, email} + full thread incl. NOTE
DeskView     reply(sc, env, ticketId, body, attachments[], resolve)   // → AWAITING or RESOLVED; audit; notifies participants
DeskView     resolve(sc, env, ticketId)                          // audit; notifies
DeskView     close(sc, env, ticketId)                            // reason SUPPORT; audit; no notification (a merchant closed nothing)
Presign      presignAttachment(sc, ticketId, filename, contentType)   // under the ticket's org prefix
```

`PlatformOverviewService` gains `support: {open, blocking_open}` from the same `ticketCounts()`
(section `support`, `degraded[]` semantics as the rest).

### Endpoints — org plane, `/api/orgs/{orgId}/support-tickets`

| method · path | role | body → response |
|---|---|---|
| `POST /` | STAFF | `{category, subject, body, blocking?, ref?: {type, id, label}, attachments?: [{object_key, content_type, file_name}]}` → `201 TicketView` · `409 TICKET_CAP` · `400` |
| `GET /` | VIEWER | `?status=open\|awaiting_merchant\|resolved\|closed&page&size` → `PageResponse<TicketSummary>`; STAFF sees own only |
| `GET /{id}` | VIEWER | `TicketView` · opaque `404` |
| `POST /{id}/messages` | STAFF (own) / MANAGER | `{body, attachments?}` → `201 TicketView` · `409 TICKET_CLOSED` |
| `POST /{id}/close` | STAFF (own) / MANAGER | → `200 TicketView` · `409 TICKET_CLOSED` |
| `POST /attachments/presign` | STAFF | `{filename, content_type}` → `{upload_url, object_key, expires_in_seconds}` · `400` off-allowlist |

`TicketSummary`: `{id, number, status, category, blocking, subject, opened_by {id, display_name},
opened_at, status_since, last_activity_at, attachment_count, last_message_preview, ref?}`.
`TicketView` adds `messages: [{id, kind, side, author {id, display_name}?, body?, status_to?,
attachments: [{id, file_name, content_type, url?}], created_at}]`, `resolved_at?`, `closed_at?`,
`closed_reason?`. Unknown `?status=` → `400` naming the four values.

### Endpoints — desk, `/api/admin/tickets`

| method · path | gate | body → response |
|---|---|---|
| `GET /` | `requireSupportDesk` | `?status=&org_id=&page&size` → `PageResponse<DeskTicketRow>` |
| `GET /counts` | `requireSupportDesk` | `?org_id=` → `DeskCounts` |
| `GET /{id}` | `requireSupportDesk` | `DeskView` · `404` |
| `POST /{id}/messages` | `requireSupportDesk` | `{body, attachments?, resolve?: bool}` → `201 DeskView` · `409 TICKET_CLOSED` |
| `POST /{id}/resolve` | `requireSupportDesk` | → `200` · `409` |
| `POST /{id}/close` | `requireSupportDesk` | → `200` · `409 TICKET_CLOSED` |
| `POST /{id}/attachments/presign` | `requireSupportDesk` | as the org plane, keyed under the ticket's org |

`DeskTicketRow`: `TicketSummary` + `org {id, name, slug, status}` (the `OrgStatus` wire form),
minus nothing the merchant list has. `DeskView`: `TicketView` + `org` + `opened_by.email` +
`NOTE` messages (none written until slice 3). Unknown `org_id` → empty page (a query narrows a
set; the timeline's 404 rule is for path segments — do not "fix" either to match).

### Errors

`kind` values on the envelope, `ApiError.ofKind`: `TICKET_CAP` (409), `TICKET_CLOSED` (409),
`TICKET_TRANSITION` (409, any other refused move). Off-allowlist content type and a bad key are
plain `400`s with the reason in `message`.

---

## Authorization

Org plane: `requireOrgAccess(req, orgId, STAFF)` for writes, `VIEWER` for reads; the handler
passes `hasManagerAuthority(sc, orgId)` and the service decides own-vs-all (the cash shift
precedent) — a STAFF member reading another's ticket gets the opaque `404`, not a `403` that
confirms it exists. Read-only impersonation cannot write (already enforced by `requireOrgAccess`).
Desk plane: `requireSupportDesk` on every route; a tenant user gets `403 "Requires a platform
role (ADMIN or SUPPORT)"`, the existing platform message.

---

## Out (deferred)

- The operator in-app feed (`GET /api/admin/notifications`), auto-close, the suspended door →
  slice 2 (`support_ticket_reach.md`). Priority, internal notes, `#` search, first-response
  metrics → slice 3.
- An email leg for staff recipients; reply-by-email; attachments other than images; a size cap
  at presign (the client downscales; the story says so instead of pretending).
- Any rate limit beyond the cap.

---

## Tests

`SupportTicketIT` (the `CounterReturnIT` harness — real services over Flyway'd Postgres):
- **Open**: STAFF opens → `OPEN`, `number` allocated, `status_since = opened_at`, the first
  `MESSAGE` row is `MERCHANT` by the opener, `attachments` stored with the org-prefixed keys; two
  desk users each get a `SUPPORT_TICKET_OPENED` notification with `org_id` = the ticket's org and an
  `IN_APP` delivery (and a `PUSH` one when a subscription exists — `TestWiring.notificationService`
  passes no push factory, so assert `IN_APP` only and note it).
- **The cap**: ten not-closed tickets → the eleventh is `TICKET_CAP`; close one → the next opens.
- **Message = transition**: merchant on `AWAITING_MERCHANT` → `OPEN` + a `STATUS` row; desk
  reply → `AWAITING_MERCHANT`, `first_response_at` set once; desk reply with `resolve` →
  `RESOLVED`; merchant message on `RESOLVED` → `OPEN` with `resolved_*` cleared; desk message on
  `RESOLVED` → still `RESOLVED`; anything on `CLOSED` → `TICKET_CLOSED`, no row written.
- **Participants**: a MANAGER who replied gets `SUPPORT_TICKET_REPLIED` alongside the opener; the
  opener deactivated and no other participant → the OWNERs get it.
- **Own vs. all**: STAFF B reading STAFF A's ticket → opaque 404; MANAGER → 200; the list for
  STAFF B carries only B's.
- **Attachment guards**: a key under another org's prefix on `attach` → 400; a stored key outside
  the prefix (written directly) → `url` null on read; `image/gif` at presign → 400.
- **Desk**: `list?status=open` orders blocking first then oldest `status_since`; `counts` equals
  the four lists' totals for the same `org_id` (pinned both directions); a SUPPORT-tier context
  passes every desk route; a tenant OWNER gets 403; every desk write leaves a `platform_audit` row
  with `org_id` = the ticket's, visible on `GET /api/admin/orgs/{orgId}/timeline`.
- **Overview**: `support.open` equals `counts().open`.

`SupportTicketMachineTest` (unit, the aggregate's table above, every cell);
`SupportTicketHandlerTest` / `TicketDeskAdminHandlerTest` (mocked services: roles per the tables,
the three `kind`s on the envelope, `?status=` 400 naming the values, the 404 shape);
`NotificationTemplatesTest` + `WhatsAppTemplatesTest` extended to the four types;
`ObjectStorageTest` for the allowlist and the prefix helpers.

## Definition of done

V97 + domain + the org-scoped repository + the `PlatformTicketRepository` sibling +
`TicketDeskPredicates` + `ticketCounts()` + both services + both handlers + `requireSupportDesk` +
`activeDeskUserIds()` + the four types/templates + the storage helpers + the overview section +
the suites above green + the measurement in V97's header; `spotless:apply`; `CLAUDE.md`'s platform
paragraph gains the desk sentence; story committed on the branch. Frontend pairs 156 and 157 ship
after this merges.
