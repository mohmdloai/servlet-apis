# The delivery contact belongs on the order, not on the customer (V80)

> **Slice N+ of the notification-reach epic** (`frontst/docs/notification-reach-epic.md`) — the
> defect the phone slice exposed. Not a new capability: it moves a fact to the row that owns it.

## The bug

A logged-in shopper sending a gift had **their own identity overwritten with the recipient's**.

`SalesOrderService.resolveKnownCustomer` (the portal checkout's customer resolver) took the
per-order delivery contact — recipient name, phone, address, from a typed block or a saved address
book row — and merged it onto the buyer's `customer` row through `upsertCustomerByEmail`. Reproduced
before the fix:

```
Nadia (customer.name = "Nadia", phone_e164 = "+201012345678")
  → portal checkout, shipping to { recipient: "Mona", phone: "01198765432", address: "3 Gift St" }
  → customer.name  becomes "Mona"
    customer.phone becomes Mona's number
```

`expected: <Nadia> but was: <Mona>`.

**Why it mattered enough to fix now.** While `customer.phone` was an inert CRM field this was an
annoyance. V79 made that column the identity a notification channel dials — so the same merge now
silently redirects **every subsequent order update for this shopper to a third party**, and the
buyer hears nothing about their own orders, until they happen to check out to their own address
again. Slice B would have shipped that to WhatsApp. It is a privacy leak and a broken channel from
one merge.

## Why it was written that way

Not carelessness — a missing column. `sales_order` had **nowhere to put a delivery contact**, and
`InvoiceService` freezes the invoice's contact block off the `Customer` object it is handed. So the
customer row was the only conduit from "what the shopper typed at checkout" to "what the invoice
prints". `docs/notifications-plan.md` §0 had already flagged the gap in the abstract:

> *"Shipping/billing address is not modelled anywhere yet; when it lands it snapshots here."*

This is that landing. One field was being asked to be both **who you are** and **where this parcel
goes**, and those diverge the moment someone buys a gift.

## The fix

**V80** adds `sales_order.delivery_recipient / delivery_phone / delivery_address` — nullable, frozen
at placement, never updated afterwards.

- **Three plain TEXT columns, not an FK to `customer_address`.** A snapshot must not move when the
  shopper later edits or deletes the address-book row it was copied from — the same freezing rule as
  `sales_order_line.unit_price` (V18) and the invoice's own contact block (V21).
- **No index, no backfill.** Nothing queries orders *by* delivery contact. And there is nothing
  honest to backfill *from*: the pre-V80 contact was merged destructively onto the customer, so
  copying it back would be a guess about which of that customer's orders it belonged to.

**Placement** freezes it (`placeReservedInTx`, inside the same savepoint as the insert, so a
rolled-back placement leaves nothing):
- **Portal** — the `DeliveryInput` it already resolved from the typed block or the saved address.
- **Anonymous** — the single `CustomerInput` block, which on that form *is* both identity and
  delivery (there is no separate address input), so the customer row keeps being written exactly as
  before. **The anonymous path is unchanged**; it is the one where merge-by-email is the whole point.
- **In-store** — no delivery contact; all three stay null.

**`resolveKnownCustomer` is now a pure read.** Nothing about the customer is written during a portal
checkout. Their contact details are edited where they should be: `PATCH /api/portal/me`.

**`InvoiceService` prefers the order's snapshot, falling back to the customer row.** That fallback is
what keeps every pre-V80 invoice rendering byte-identically — those orders carry no snapshot. The
name follows the same rule, so the block stays **internally coherent**: a name printed above a phone
and address belonging to someone else would be worse than either choice alone. Email is deliberately
*not* part of the delivery block — it is the billing identity and the address the order-view link
was sent to.

**`SalesOrderResponse` carries the three fields on the staff plane.** This is required, not
decorative: staff answering *"where do I ship it?"* previously read it off the CRM record, which only
worked **because** of the corruption. With the merge gone the CRM row correctly shows the buyer, so
without this the merchant could not ship a portal gift order at all. Withheld from
`forCustomerView` exactly like `notes` — an anonymous magic link may be forwarded, and a home
address is not something to hand whoever ends up holding that URL.

## Two behaviour changes, stated plainly

1. **A portal checkout no longer updates the customer's name/phone/address.** The address book
   already stores the reusable copy, so nothing is lost. Two existing assertions in
   `PortalCheckoutIT` codified the old behaviour and now assert the new contract (the snapshot is on
   the order).
2. **A portal gift order's invoice is unchanged**, because the name/phone/address it prints now come
   from the order rather than from a customer row that had been overwritten with the same values.

## Acceptance criteria

- [x] A portal checkout to a different recipient leaves `customer.name` and `customer.phone_e164`
      untouched.
- [x] That recipient's name/phone/address are frozen on the order.
- [x] A saved address-book row's snapshot is copied onto the order, not onto the customer.
- [x] The staff order read exposes all three; the anonymous magic-link view exposes none.
- [x] Anonymous checkout is unchanged — it still creates/merges the guest customer from the single
      contact block, and now also snapshots it on the order.
- [x] Invoices for pre-V80 orders (no snapshot) render exactly as before, via the customer fallback.

## Tests

`PortalCheckoutIT` — the gift case (identity intact + snapshot present + staff/anon DTO split), plus
the two updated assertions. **Full backend sweep: 1040 ITs green**, unit modules common 57 /
domain / service 212. The invoice-heavy suites are the ones that matter here and all pass untouched:
`DeliverInvoiceIT`, `InvoiceVoidReissueIT`, `InvoiceRollupVerificationIT`, `CreditNoteRefundIT`,
`PortalInvoicesIT`.

## Out

- **Exposing the delivery contact on the portal order page.** The shopper typed it and it is their
  own order, so it is defensible — but it is a customer-facing surface change with a frontend story
  attached, and no urgency. `forCustomerView` withholds it for now.
- **A re-address flow.** Changing where a parcel goes after placement has its own stock and money
  questions; the snapshot is deliberately write-once.
- **`customer_address.phone` → E.164.** Still untouched (see `phone_e164_normalization.md` §Out);
  it becomes interesting when a rider needs to dial it.
- **The walk-in shared-email hazard.** An in-store sale keyed to a shared address (e.g.
  `walkin@shop.test`) merges every walk-in into one customer row. Pre-existing, out of scope here,
  and partly why in-store deliberately fires no customer notifications.
