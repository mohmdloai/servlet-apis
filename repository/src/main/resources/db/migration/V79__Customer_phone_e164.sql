-- The canonical, dialable form of customer.phone — the gate in front of any phone-based
-- notification channel (WhatsApp/SMS). See stories/phone_e164_normalization.md and the epic
-- frontst/docs/notification-reach-epic.md.
--
-- WHY A SECOND COLUMN, not an in-place rewrite: this mirrors the split V62 already established
-- between a faithful STORAGE form and a derived MATCH form (customer.name / customer.name_search).
-- `phone` keeps exactly what the shopper typed — that is what a support agent reads back on a call
-- — and `phone_e164` is the machine form. A re-parse can therefore never lose the original, and
-- improving the parser later is a backfill, not a data-loss event.
--
-- NULLABLE ON PURPOSE, and null is a real answer: it means "we could not turn what they typed into
-- a dialable number", which is a suppressed channel, exactly like a customer with no email address.
-- Checkout never rejects a phone (the fail-open decision), so NOT NULL would be a lie.

ALTER TABLE customer
    ADD COLUMN phone_e164 TEXT;

COMMENT ON COLUMN customer.phone_e164 IS
    'Canonical E.164 (+201012345678) derived from phone by common Phone.toE164; NULL = unparseable, i.e. unreachable by phone-based channels. Written at every ingress; never edited directly.';

-- Backfill. This SQL mirrors common/.../text/Phone.java, which is the LIVE definition — every
-- ingress writes the column from Java and nothing recomputes it in the database. That makes drift
-- a non-issue by construction: this expression runs exactly once, here.
--
-- Deliberately narrower than the Java: it canonicalizes only the two unambiguous Egyptian mobile
-- shapes and already-plus-prefixed numbers, and leaves everything else NULL for the parser to pick
-- up the next time that customer's record is written. A backfill that guesses is worse than one
-- that under-reaches, because a wrong number in this column is a message sent to a stranger.
--
-- Arabic-Indic/Persian digits were already folded to ASCII by V62's norm_numeric backfill over this
-- same column, so this expression can safely assume ASCII digits.
WITH digits AS (
    SELECT id,
           phone,
           regexp_replace(phone, '[^0-9]', '', 'g') AS d,
           phone LIKE '+%'                          AS plus_prefixed
    FROM customer
    WHERE phone IS NOT NULL AND phone <> ''
)
UPDATE customer c
SET phone_e164 = CASE
    -- 01[0125] + 8 digits — the way an Egyptian writes their mobile.
    WHEN d ~ '^01[0125][0-9]{8}$'   THEN '+20' || substring(d from 2)
    -- The same mobile with the trunk prefix omitted (typed from memory). Not a guess: 1[0125] + 8
    -- digits is unambiguously an Egyptian mobile, and this shape is live in the seeded data.
    WHEN d ~ '^1[0125][0-9]{8}$'    THEN '+20' || d
    -- 00 20 1… / 20 1… — country-coded, with or without the international access code.
    WHEN d ~ '^0020(1[0125][0-9]{8})$' THEN '+' || substring(d from 3)
    WHEN d ~ '^20(1[0125][0-9]{8})$'   THEN '+' || d
    -- Already international and within E.164's 15-digit cap: keep it as given.
    WHEN plus_prefixed AND d ~ '^[1-9][0-9]{7,14}$' THEN '+' || d
    ELSE NULL
    END
FROM digits
WHERE c.id = digits.id;
