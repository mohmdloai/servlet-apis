-- Phase 2 of stories/payment_claim_verify.md (stories/payment_claim_not_found.md): when a manager
-- cannot find a claimed transfer they pick a reason (V90's not_found_reason — NO_TRANSFER,
-- DIFFERENT_ACCOUNT, OTHER) and may add a free note for the shopper ("nothing arrived under this
-- reference between 11 and 13 Jul"). The note is what the shopper reads on the order page and in
-- the PAYMENT_NOT_FOUND email; a code alone is not a sentence. Cleared with the reason when the
-- claim is re-opened or verified.
ALTER TABLE payment_transaction
  ADD COLUMN not_found_note TEXT;
