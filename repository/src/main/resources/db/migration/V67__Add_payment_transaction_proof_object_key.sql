-- Shopper payment-proof claim (roadmap item 2, stories/shopper_payment_proof_claim.md).
-- A shopper attaches an InstaPay screenshot to their payment claim. Unlike the free-text
-- verification_proof (staff-written, rendered verbatim in the worklist), this is an object-storage
-- key ({orgId}/payment-proof/{orderId}/…) that must be presigned to view — a categorically
-- different value, so it gets its own column. Nullable: a claim may carry only a reference.
ALTER TABLE payment_transaction
    ADD COLUMN proof_object_key TEXT;
