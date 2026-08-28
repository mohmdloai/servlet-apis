# Slice: Verify the claim, don't re-record it — phase 3 (record a different transfer from a claim)

> Branch **`169_feat/payment-claim-supersede`** off `168_feat/payment-claim-not-found` (merges after
> it), **no migration**. The "small backend addition on the record endpoint" phase 3 of
> `ststore/design/payment-claims.html` needs; frontend twin `frontst/stories/134_st_claim_record_polish.md`.

## The case

The shopper's screenshot shows the right transfer — under a reference that differs from what they
typed (a digit off, a different field copied). The manager should not have to mark the claim not
found, wait for a re-file, and re-type; nor open a blank record form and lose the shopper and the
screenshot on the way. "Record a different transfer" **from the claim card** records the reference
the screenshot shows, pre-bound to the order, carrying the claim's shopper and screenshot, and
answers the claim in the same submit.

## What ships

`POST /payment-transactions` takes **`supersedes_claim_id`** (UUID, optional):

- The claim must be open (UNVERIFIED or NOT_FOUND) in this org → else 409 (VERIFIED / ABANDONED)
  / 404.
- The new row inherits the claim's `claimed_by_customer_id` (unless the body names one) and its
  `proof_object_key` — the screenshot is the evidence for **this** transfer now — but not
  `claimed_sales_order_id` (the record is not a shopper claim).
- The pending-claim guard treats the superseded claim as acknowledged (the manager is answering
  it); any **other** open claim still needs `acknowledge_claim_ids`.
- Once the new row exists: order lock, then the claim lock, then `PaymentTransaction.supersede(now)`
  — NOT_FOUND with the system reason **`REFERENCE_DIFFERS`** (an earlier manual note is kept). The
  order is then reconciled through the new row; when it goes PAID the claim closes ABANDONED in the
  same transaction, the reason surviving on the row as the record of why. No `PAYMENT_NOT_FOUND`
  mail — the shopper's order is paid and the ORDER_PAID mail says so.
- `VerifyCommand` gains `supersedesClaimId` (12-arg canonical; the 10- and 11-arg shapes remain).

## Tests

`PaymentClaimNotFoundIT.recordSupersedingAClaim_…`: the record is MATCHED and pays the order, the
shopper + proof key travel, the claim is ABANDONED with `REFERENCE_DIFFERS`, no not-found
notification, a VERIFIED claim cannot be superseded (409).
