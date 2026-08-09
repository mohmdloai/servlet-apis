# Story 143 — the approval refusal becomes machine-readable (D12)

**Type:** fix · **Branch:** `143_fix/approval-403-error-shape` (stacked on
`142_docs/approval-403-not-machine-readable`) · **Plan:** `docs/defect-remediation-plan.md` §D12 ·
**Client half:** `frontst` story 94

A MANAGER who trips the refund/credit-note approval threshold could not be told why. The 403 said so
only in its message string, which no client is supposed to render, and the fields the admin app
already declares for this state were never populated — so its accurate copy ("This needs OWNER
approval.") was unreachable and something wrong showed instead.

Two commits, deliberately: the refactor that makes the fix small, then the fix.

---

## Part 1 — one place decides what an exception looks like

**Was:** every handler owned a private `writeError(HttpServletResponse, AppException)`, 33 of them,
each building `ApiError.of(status, message)` by hand. Two also hand-rolled the
`InsufficientStockException` shortage mapping (`SalesOrderHandler`, `FulfillmentHandler`), and
`AuthServlet` had a local `instanceof` for D9a's `Retry-After`. That was tolerable while only one
exception carried extra data and only two routes could raise it.

It stops being tolerable at D12, which surfaces from **five** handlers, because three throw sites
fan out:

| Throw site | Reaches | Route |
|---|---|---|
| `RefundService.create` | `RefundHandler` | `POST /refunds` |
| `CreditNoteService.issue` | `CreditNoteHandler` | `POST /credit-notes` |
| `OrderCancellationService` | `SalesOrderHandler` | `POST /sales-orders/{id}/cancel` |
| `RefundService.createDirectPendingInTx` | `FulfillmentHandler` | `POST /fulfillments/{id}/refund` |
| `RefundService.createDirectPendingInTx` | `PaymentTransactionHandler` | `POST /payment-transactions/{id}/refund` |

Five hand-written copies is five chances to write four. A refusal that is machine-readable on
`/refunds` but not on `/fulfillments/{id}/refund` is **worse than one that is machine-readable
nowhere**, because the client cannot tell which it is holding.

**Now:** `ApiErrors` (`api/dto`) is the one place. `body(AppException)` returns the response body
including whatever the exception's type carries; `applyHeaders(resp, e)` adds what belongs in
headers rather than fields (today `Retry-After`). Every handler's `writeError` is now two lines, and
a new carrying exception is added **once**.

This does not centralise *writing* — each handler keeps its own `writeJson`, which owns its
`ObjectMapper` and status. Only the mapping moved, which is the part that was duplicated.

**Proof:** the full **1012 api ITs** pass unchanged. That is the real assertion here — the refactor
touches every error path in the application, so anything narrower would be hoping rather than
knowing. The two structured cases are covered explicitly: the shortage body by
`PublicCheckoutIT` / `PortalCheckoutIT` / `VariantCommerceIT` / `FailedFulfillmentHandlerAuthTest`,
and `Retry-After` by `LoginThrottleIT`.

## Part 2 — the refusal carries its reason

`ApprovalRequiredException extends AuthorizationException` — same 403, same message, plus
`requiredRole` / `thresholdAmount` / `requestedAmount` (decimal EGP, the unit
`org.refund_approval_threshold` is stored in). `ApiError` gains the three optional fields via
`ofApprovalRequired`, following the `ofShortages` precedent; Jackson omits nulls, so **an ordinary
403 is byte-identical to what it was** — asserted, not assumed.

**`requestedAmount` is the point.** Since D1 it is the money source's *running total*, not the
amount of the call in front of the user: 300 + 300 against one payment, or an invoice's cumulative
credited total. A client comparing only the amount it can see cannot derive that, which is exactly
why the pre-warning stays silent and then the refusal lands. Putting it on the wire is what lets the
client explain a number the user did not type.

**`requiredRole` is a field, not a constant.** It is `OWNER` today and reads from `OrgRole.OWNER`;
the client renders whatever arrives rather than assuming.

**Proof:** `CreditNoteRefundIT` — `threshold_refusalCarriesTheRoleAndTheNumbers` (the payment's
running total, 600, against a 500 bar), `threshold_creditNoteRefusalCarriesTheRoleAndTheNumbers`
(the invoice's cumulative total), and `threshold_refusalSerializesTheFields`, which asserts through
`ApiErrors.body` — the shared mapping every one of the five routes now uses, so proving it once
proves them all rather than proving one handler and hoping. That last test also pins the
no-regression half: a plain `AuthorizationException` still produces a body with all three fields
null.

---

## Contract change

| Surface | Before | After |
|---|---|---|
| Above-threshold 403 (5 money routes) | `{status, error, message}` | adds `required_role`, `threshold_amount`, `requested_amount` |
| Every other 403 | `{status, error, message}` | unchanged, byte for byte |

Additive and null-omitted, so no client breaks; `frontst` story 94 consumes it.

## What this does not fix

- **The client-side pre-warning is still optimistic.** `needsOwnerApproval` compares *this* amount
  while the server compares the running total, so a MANAGER can still see no warning and then meet
  the 403. That is now a *good* refusal rather than a confusing one, which was the goal; making the
  warning itself accurate needs the running total before submit — a new read, and a separate
  decision about whether a pre-warning is worth a round trip.
- **The 24 raw-message call sites** in the admin app (see the plan's correction under D12). Only the
  approval refusal is forced onto localised copy; the rest keep the backend's own wording, which is
  usually the most specific thing available and in several places is deliberately *parsed*.

## No migration

Nothing here changes the schema.
