-- Slice: Unicode text normalization at the ingress boundary (stories/unicode_text_normalization.md).
--
-- The app now normalizes all externally-supplied text to a canonical form in Java (common Text util)
-- at the DTO→domain boundary: storage form = NFC + strip bidi/zero-width controls + collapse
-- whitespace; numeric form additionally folds Arabic-Indic/Persian digits to ASCII; a lossy MATCH
-- form (foldForSearch) unifies Arabic letter variants for search. This migration does the DB half:
--   1. canonical SQL fold functions that MIRROR the Java util (fold_search is the runtime contract
--      behind the generated *_search columns; norm_text/norm_numeric are one-shot backfill helpers).
--   2. an idempotent backfill so pre-slice rows share the one canonical form as new input.
--   3. derived, generated *_search columns (customer/product name) + trigram indexes for
--      Arabic-aware retrieval — GENERATED ALWAYS ... STORED so they can never drift from the source.
--   4. ICU (und-x-icu) collation on the human name columns so ORDER BY name sorts linguistically,
--      not by code point (and converts the varchar(n) name columns to text per the column-typing
--      guardrail).
--
-- All invisible code points are written as U&'\XXXX' escapes so the classes are reviewable; only the
-- visible Arabic letters/digits in the letter-fold map are literals. Runbook for the ICU dependency
-- lives in docs (a libicu major upgrade → REINDEX the *_search / name indexes; no data change).

-- ── 1. Canonical SQL normalizers (IMMUTABLE, mirroring common/.../text/Text.java) ────────────────

-- MATCH form. Mirrors Text.foldForSearch: NFC, delete harakat (U+064B–0652), superscript alef
-- (U+0670), tatweel (U+0640), standalone hamza (U+0621) and all bidi/zero-width controls incl. the
-- joiners; unify alef seats (أإآٱ→ا), waw-hamza (ؤ→و), yaa-hamza (ئ→ي), alef maqsura (ى→ي), taa
-- marbuta (ة→ه); fold Arabic-Indic + Persian digits to ASCII; collapse whitespace; lowercase.
-- Lossy — used only as the derived *_search key. IMMUTABLE so it can drive a generated column.
CREATE OR REPLACE FUNCTION fold_search(t text) RETURNS text AS $fn$
  SELECT nullif(
    lower(btrim(regexp_replace(
      translate(
        translate(
          normalize(t, NFC),
          U&'\064B\064C\064D\064E\064F\0650\0651\0652\0670\0640\0621\200B\200C\200D\200E\200F\202A\202B\202C\202D\202E\2066\2067\2068\2069\FEFF',
          ''),
        U&'\0623\0625\0622\0671\0624\0626\0649\0629\0660\0661\0662\0663\0664\0665\0666\0667\0668\0669\06F0\06F1\06F2\06F3\06F4\06F5\06F6\06F7\06F8\06F9',
        U&'\0627\0627\0627\0627\0648\064A\064A\064701234567890123456789'),
      U&'[[:space:]\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]+', ' ', 'g'))),
    '')
$fn$ LANGUAGE sql IMMUTABLE;

-- STORAGE form for display text. Mirrors Text.normalizeText: NFC, strip bidi/zero-width controls but
-- KEEP the ZWNJ/ZWJ joiners (orthographically meaningful in Arabic/Persian), collapse whitespace,
-- trim, empty → NULL. One-shot backfill helper (dropped at the end of this migration).
CREATE OR REPLACE FUNCTION norm_text(t text) RETURNS text AS $fn$
  SELECT nullif(
    btrim(regexp_replace(
      translate(normalize(t, NFC),
        U&'\200B\200E\200F\202A\202B\202C\202D\202E\2066\2067\2068\2069\FEFF', ''),
      U&'[[:space:]\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]+', ' ', 'g')),
    '')
$fn$ LANGUAGE sql IMMUTABLE;

-- STORAGE form for numeric-semantic text (phone/refs/barcode/tracking). Mirrors Text.normalizeNumeric:
-- norm_text PLUS strip the joiners too and fold Arabic-Indic/Persian digits to ASCII. One-shot helper.
CREATE OR REPLACE FUNCTION norm_numeric(t text) RETURNS text AS $fn$
  SELECT nullif(
    btrim(regexp_replace(
      translate(
        translate(normalize(t, NFC),
          U&'\200B\200C\200D\200E\200F\202A\202B\202C\202D\202E\2066\2067\2068\2069\FEFF', ''),
        U&'\0660\0661\0662\0663\0664\0665\0666\0667\0668\0669\06F0\06F1\06F2\06F3\06F4\06F5\06F6\06F7\06F8\06F9',
        '01234567890123456789'),
      U&'[[:space:]\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]+', ' ', 'g')),
    '')
$fn$ LANGUAGE sql IMMUTABLE;

-- ── 2. Backfill existing rows to the canonical form (idempotent: each guarded by IS DISTINCT) ─────
-- Display / identity fields.
UPDATE customer SET name = norm_text(name)
  WHERE name IS NOT NULL AND name IS DISTINCT FROM norm_text(name);
UPDATE customer SET address = norm_text(address)
  WHERE address IS NOT NULL AND address IS DISTINCT FROM norm_text(address);
UPDATE customer SET phone = norm_numeric(phone)
  WHERE phone IS NOT NULL AND phone IS DISTINCT FROM norm_numeric(phone);
UPDATE product SET name = norm_text(name)
  WHERE name IS DISTINCT FROM norm_text(name);
UPDATE product SET description = norm_text(description)
  WHERE description IS NOT NULL AND description IS DISTINCT FROM norm_text(description);
UPDATE org SET name = norm_text(name)
  WHERE name IS DISTINCT FROM norm_text(name);
UPDATE category SET name = norm_text(name)
  WHERE name IS DISTINCT FROM norm_text(name);

-- Notes / free-text refs.
UPDATE sales_order SET notes = norm_text(notes)
  WHERE notes IS NOT NULL AND notes IS DISTINCT FROM norm_text(notes);
UPDATE refund SET notes = norm_text(notes)
  WHERE notes IS NOT NULL AND notes IS DISTINCT FROM norm_text(notes);
UPDATE credit_note SET reason_note = norm_text(reason_note)
  WHERE reason_note IS NOT NULL AND reason_note IS DISTINCT FROM norm_text(reason_note);
UPDATE fulfillment SET carrier = norm_text(carrier)
  WHERE carrier IS NOT NULL AND carrier IS DISTINCT FROM norm_text(carrier);
UPDATE fulfillment SET notes = norm_text(notes)
  WHERE notes IS NOT NULL AND notes IS DISTINCT FROM norm_text(notes);
UPDATE fulfillment SET tracking_number = norm_numeric(tracking_number)
  WHERE tracking_number IS NOT NULL AND tracking_number IS DISTINCT FROM norm_numeric(tracking_number);
UPDATE payment_transaction SET customer_note = norm_text(customer_note)
  WHERE customer_note IS NOT NULL AND customer_note IS DISTINCT FROM norm_text(customer_note);
UPDATE payment_transaction SET verification_proof = norm_text(verification_proof)
  WHERE verification_proof IS NOT NULL AND verification_proof IS DISTINCT FROM norm_text(verification_proof);

-- UNIQUE-constrained fields: normalize only where it does NOT collide with an existing row (a genuine
-- case-/digit-variant duplicate is left as-is for manual merge rather than failing the migration).
UPDATE app_user u SET email = lower(btrim(normalize(u.email, NFC)))
  WHERE u.email IS DISTINCT FROM lower(btrim(normalize(u.email, NFC)))
    AND NOT EXISTS (SELECT 1 FROM app_user x
                    WHERE x.id <> u.id AND x.email = lower(btrim(normalize(u.email, NFC))));
UPDATE customer c SET email = lower(btrim(normalize(c.email, NFC)))
  WHERE c.email IS DISTINCT FROM lower(btrim(normalize(c.email, NFC)))
    AND NOT EXISTS (SELECT 1 FROM customer x
                    WHERE x.org_id = c.org_id AND x.id <> c.id
                      AND x.email = lower(btrim(normalize(c.email, NFC))));
UPDATE product p SET barcode = norm_numeric(p.barcode)
  WHERE p.barcode IS NOT NULL AND p.barcode IS DISTINCT FROM norm_numeric(p.barcode)
    AND NOT EXISTS (SELECT 1 FROM product x
                    WHERE x.org_id = p.org_id AND x.id <> p.id
                      AND x.barcode = norm_numeric(p.barcode));
UPDATE payment_transaction t SET provider_ref = norm_numeric(t.provider_ref)
  WHERE t.provider_ref IS NOT NULL AND t.provider_ref IS DISTINCT FROM norm_numeric(t.provider_ref)
    AND NOT EXISTS (SELECT 1 FROM payment_transaction x
                    WHERE x.id <> t.id AND x.provider = t.provider
                      AND x.provider_ref = norm_numeric(t.provider_ref));

-- ── 3. ICU collation on the human name columns (varchar(n) → text per the column-typing guardrail) ─
-- und-x-icu is a DETERMINISTIC collation: ordering uses ICU, equality stays byte-exact — so no unique
-- constraint or equality lookup changes behaviour; only ORDER BY name now sorts in Arabic/Latin
-- collation order instead of code-point order. No name index exists to rebuild.
ALTER TABLE customer ALTER COLUMN name TYPE text COLLATE "und-x-icu";
ALTER TABLE product  ALTER COLUMN name TYPE text COLLATE "und-x-icu";
ALTER TABLE category ALTER COLUMN name TYPE text COLLATE "und-x-icu";
ALTER TABLE org      ALTER COLUMN name TYPE text COLLATE "und-x-icu";

-- ── 4. Derived match-key columns + trigram indexes ───────────────────────────────────────────────
-- GENERATED ALWAYS ... STORED: PG computes it for every existing row on ADD and recomputes on every
-- write, so the search key can never drift from name and no application write-path code is needed.
ALTER TABLE customer ADD COLUMN IF NOT EXISTS name_search text
  GENERATED ALWAYS AS (fold_search(name)) STORED;
ALTER TABLE product ADD COLUMN IF NOT EXISTS name_search text
  GENERATED ALWAYS AS (fold_search(name)) STORED;

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS customer_name_search_trgm_idx
  ON customer USING gin (name_search gin_trgm_ops);
CREATE INDEX IF NOT EXISTS product_name_search_trgm_idx
  ON product USING gin (name_search gin_trgm_ops);

-- ── 5. Retire the one-shot backfill helpers; fold_search stays (drives the generated columns) ─────
DROP FUNCTION norm_text(text);
DROP FUNCTION norm_numeric(text);
