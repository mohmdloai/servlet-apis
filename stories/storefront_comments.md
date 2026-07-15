# Slice R2: Listing comments — customer asks, merchant replies, reply notifies

> Reviews (R1) let a buyer speak *after* delivery. Comments serve the moment *before* the sale: a
> logged-in shopper leaves a question or comment on a listing ("does the kettle come with the
> cordless base?"), the merchant answers, the pair publishes together, and the customer is notified
> through the P5 feed + email. **A comment is one customer message + at most one official merchant
> reply** — no threads, no crowd (epic §5) — and it becomes public **only when the merchant
> replies**: answering *is* the moderation act (epic §4).
>
> Canonical decisions: [`frontst/docs/storefront-reviews-epic.md`](../../frontst/docs/storefront-reviews-epic.md)
> (§1, §4–§7, §9). Reuses R1's three-plane pattern and the P5 notification machinery
> (`portal_notifications.md`) verbatim. Feeds frontend story 41.

---

## Goal

A logged-in customer (no purchase required — epic §2) comments on a listing; the merchant replies
from a staff worklist, which simultaneously publishes the Q&A pair, raises a `COMMENT_REPLIED`
notification to that customer (durable feed row + email leg, preference-honoring), and nothing
unanswered or dismissed ever reaches the public read.

## Design

- **Migration (next `V##`)** — `listing_comment` (UUID PKs like every business table —
  `product_listing.id` is a UUID, V40):
  ```
  id UUID PK DEFAULT gen_random_uuid() · org_id UUID NOT NULL REFERENCES org(id)
  product_listing_id UUID NOT NULL REFERENCES product_listing(id)
  customer_id UUID NOT NULL REFERENCES customer(id) ON DELETE CASCADE
  body TEXT NOT NULL                                -- ≤ 1000 (service); plain text, verbatim
  display_name TEXT NOT NULL                        -- frozen at write (R1/epic §6)
  reply_body TEXT NULL                              -- ≤ 2000; the one official answer
  replied_by UUID NULL REFERENCES app_user(id)      -- audit; never serialized publicly
  replied_at TIMESTAMPTZ NULL
  status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ANSWERED','DISMISSED'))
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
  CHECK ((status = 'ANSWERED') = (reply_body IS NOT NULL))   -- answered ⇔ replied, structurally
  ```
  Indexes: `(product_listing_id, status, created_at DESC)` (public), `(org_id, status, created_at)`
  (queue), partial `(customer_id, created_at DESC)` (mine). jOOQ codegen. **No** per-customer
  uniqueness — multiple questions per listing are legitimate; the flood control is the rate bucket
  + a service cap (≤ 5 PENDING per customer per listing → 400).
- **Notification type** — **no migration**: `notification.type` is deliberately an open TEXT
  column with no CHECK (V44's design — "new event types must not need a migration"). The work is
  Java-side: add `NotificationType.COMMENT_REPLIED` **and** its `NotificationTemplates.render`
  case (title + body + the email-HTML leg — the switch is exhaustive, so the compiler enforces
  the template exists). CUSTOMER recipient; `channelsFor(CUSTOMER) = [IN_APP, EMAIL]` already
  (P5), and the preferences PUT accepts the new type for free (`validatePreferenceType` walks the
  enum). The notify() call runs **inside the reply transaction** (the
  durable-row-is-the-guarantee rule) with `listing_slug` in the payload;
  `PortalNotificationResponse` gains a nullable `listing_slug` (alongside the existing
  `order_number`, extracted from the payload the same way) so the portal feed can deep-link to
  the listing's Q&A anchor — additive, staff feed untouched.
- **Service** — `ListingCommentService`:
  - `submit(orgId, customerId, listingSlug, body)` — listing exists in-org (opaque 404), body
    1..1000 (400), PENDING-per-listing cap (400), freezes `display_name`; status PENDING;
  - `myComments(orgId, customerId)` (with status + reply, newest) / `deleteOwn(...)` — deleting an
    ANSWERED comment removes the public pair too (the customer owns their words); foreign → opaque 404;
  - `reply(orgId, actorUserId, commentId, replyBody)` — PENDING (or re-reply on ANSWERED to *edit
    the answer* — allowed, status stays ANSWERED, no re-notification) → sets reply fields, status
    ANSWERED, **notifies** `COMMENT_REPLIED` in-txn (first reply only);
  - `dismiss(orgId, commentId)` — PENDING → DISMISSED (never public, no notification — silence,
    not rejection-nagging);
  - `publicPage(orgId, listingId, page, size)` — ANSWERED only.
- **Portal API** (`PortalServlet`; `X-Portal-Request` + Origin; bucket `rl:portal-comment`):
  ```
  POST   /api/portal/comments        {listing_slug, body}    → 201
  GET    /api/portal/comments        → mine (listing slug+title, body, status, reply_body?, replied_at?)
  DELETE /api/portal/comments/{id}
  ```
  `private, no-store`.
- **Staff API** — `CommentHandler` under `OrgServlet` (worklist conventions):
  ```
  GET  /api/orgs/{orgId}/comments?status=&page=&size=    (VIEWER; filtered = queue ASC, ledger DESC)
  POST /api/orgs/{orgId}/comments/{id}/reply {body}      (STAFF — publishes + notifies)
  POST /api/orgs/{orgId}/comments/{id}/dismiss           (STAFF)
  ```
- **Public read** (`PublicStorefrontServlet`, `rl:pub-read`):
  ```
  GET /api/public/{orgSlug}/listings/{listingSlug}/comments?page=&size=
  ```
  The `{listingSlug}` resolves through the **same PUBLISHED-only resolution as the listing read**
  (never a bare slug lookup — the R1 rule), so Q&A on a DRAFT/ARCHIVED listing is unreachable by
  construction. ANSWERED only, `created_at DESC`, paged, `max-age=60`. Row:
  `{display_name, body, created_at, reply_body, replied_at}` — never `customer_id`/`replied_by`/ids
  (epic §6). No aggregate — a Q&A count sells nothing; the section renders or it doesn't.

## Scope

### In
The migration (the `listing_comment` table) + codegen; `NotificationType.COMMENT_REPLIED` + its
template; service; the three plane surfaces; `PortalNotificationResponse.listing_slug`; DTOs.

### Out (deferred)
Threads / customer replies (epic §5); votes; merchant-initiated announcements; extending replies to
*reviews* (the fields and pattern here are the template when asked); auto-close of stale PENDING;
Q&A search.

## Authorization

Portal: session-keyed own-only, opaque 404s. Staff: VIEWER read / STAFF reply+dismiss.
Public: anonymous, whitelisted, GET-only. Reply audit (`replied_by`) is internal forever.

## Acceptance criteria

1. Submit: logged-in, no purchase needed → 201 PENDING; body empty or >1000 → 400; 6th PENDING on
   one listing → 400; unknown slug → opaque 404; anonymous/staff-`aud` → rejected by the portal
   filter (regression).
2. Reply: publishes the pair (appears in the public read), sets ANSWERED ⇔ reply present (the CHECK
   holds), and creates exactly one `COMMENT_REPLIED` notification for that customer with a feed row
   (in-txn) + email leg honoring the `(org,customer)` preference opt-out; the portal feed row
   carries `listing_slug`. Editing the answer (re-reply) updates `reply_body` and does **not**
   re-notify.
3. Dismiss: PENDING → DISMISSED, never public, no notification; dismissed is terminal (reply on
   DISMISSED → 409).
4. Public read: ANSWERED only (PENDING/DISMISSED fixtures absent), newest-first, whitelisted (JSON
   scan), `max-age=60`, non-GET → 405.
5. Mine: lists my comments with status + reply; own-delete removes an ANSWERED pair from the
   public read; foreign id → opaque 404.
6. Queue conventions: `?status=PENDING` oldest-first, unfiltered ledger newest-first, unknown
   status → 400.

## Tests

`PortalCommentsIT` (AC 1, 5), `CommentReplyIT` (AC 2–3, 6 — asserts the in-txn notification row +
channel legs the P5 ITs pattern established), `PublicCommentsIT` (AC 4 + no-leak scan). Regression:
all P5 notification ITs green with the new enum constant (channel-scoped counts unchanged);
`PortalAuthIT` untouched. Verified live through Tomcat: login → comment → staff reply → public
pair + portal bell shows the notification with the listing deep-link.

## What this unblocks

| Next | Depends on this |
|---|---|
| **Frontend story 41** — Q&A on the listing page, "My questions" portal section, admin answer queue | the whole surface |
| Merchant replies to reviews | `reply_body`/`replied_at`/notify pattern, copied onto `listing_review` |
