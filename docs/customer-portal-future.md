# Customer Self-Serve Portal — Future Design (Magic Links)

**Status:** Not built. Captured here so the design is ready when the first product requirement lands ("track my order", "view my invoice"). Today, customers are pure CRM records (`customer` table, no auth). Adding magic links is *additive* — no rework of the current schema or auth machinery.

## Trigger

Build this when the first feature needs a customer to perform a read-only self-serve action — typically:

- Track an order via a link in a confirmation email
- Download or view an invoice / receipt
- View their own purchase history

If the requirement is staff-driven (a salesperson looks up a customer's orders), do **not** build this — keep the staff-as-actor model.

## Why magic links, not passwords

- Customers are not power users; password management is friction and a support cost.
- One narrow capability per click is safer than a broad logged-in session.
- Email is already the contact channel — the security boundary doesn't move.
- No collision with `app_user.email` global uniqueness (staff identities live there).

## Schema

```sql
CREATE TABLE customer_magic_token (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id   UUID         NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    org_id        UUID         NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    token_hash    VARCHAR(64)  NOT NULL UNIQUE,        -- SHA-256 hex (raw token never stored)
    purpose       VARCHAR(64)  NOT NULL,               -- e.g. 'view_order', 'view_invoice'
    resource_id   UUID,                                -- the order/invoice this token unlocks
    expires_at    TIMESTAMPTZ  NOT NULL,
    used_at       TIMESTAMPTZ,                         -- single-use enforcement
    issued_to_ip  INET,                                -- audit only; not used to verify
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Hot lookup path: only unused tokens are interesting.
CREATE INDEX customer_magic_token_lookup
    ON customer_magic_token (token_hash) WHERE used_at IS NULL;

-- Cleanup job target.
CREATE INDEX customer_magic_token_cleanup
    ON customer_magic_token (expires_at);
```

### Design notes (don't change without a reason)

- **Narrow scope (`purpose` + `resource_id`):** never issue blanket "log this customer in" tokens. Each link unlocks one resource. If a customer's email is compromised, the blast radius is one order, not their account history.
- **SHA-256, not bcrypt:** tokens are single-use, short-lived, full-entropy random. SHA-256 hex is correct and ~1000× faster per click.
- **Partial index `WHERE used_at IS NULL`:** the lookup never wants old tokens; index stays small as the table accumulates history.
- **`ON DELETE CASCADE`:** deleting a customer or org wipes their pending tokens.

## Click flow

```
GET /api/customer/magic/{rawToken}
  ↓
AuthService.consumeMagicToken(rawToken):
    SHA-256 the token
    SELECT FOR UPDATE WHERE token_hash = ? AND used_at IS NULL AND expires_at > now()
    UPDATE used_at = now()                         -- atomic single-use
    issue short-lived JWT:
      sub      = customer_id
      actor    = CUSTOMER
      org_id   = <token's org_id>
      actions  = ['view_order:abc-123']            -- narrow, from purpose+resource_id
      ttl      = 15 min
    NO refresh token. When the JWT expires, the customer clicks the email link again.
  ↓
Set access_token cookie, 302 redirect to /portal/orders/abc-123 (strip the token from URL)
```

## What re-enables when this ships

These were intentionally *not* added in the CRM-only refactor; switch them on with this feature:

- Add `CUSTOMER` value to `actor_type` enum (new migration).
- Add `ActorContext.customer(...)` factory and `SecurityContext.isCustomer()`.
- Add `/api/customer/magic/*` to `JwtAuthFilter` skip-paths.
- Add rate-limit buckets in `RateLimitFilter`:
  - `rl:magic-issue:{ip}` — prevent enumeration / inbox-spam DoS
  - `rl:magic-issue:{customer_id}` — same for a known customer
- Cleanup cron (or scheduled task) that deletes rows `WHERE expires_at < now() - interval '7 days'`.

## Risks to revisit

1. **Email is now a security boundary.** Same risk as password reset, mitigated by narrow per-resource tokens.
2. **Token leakage via Referer or browser history.** Mitigated by single-use + short TTL + 302 to clean URL after consumption.
3. **Cross-org tokens.** A customer that exists in two orgs needs two separate tokens; the `org_id` column makes the boundary explicit and queryable.

## Estimated scope

~150 lines of new code: one migration, one repository, one service method, one servlet endpoint, one rate-limit bucket, one filter skip-path. No changes to existing org/staff auth.
