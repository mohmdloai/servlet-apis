# Fix: let staff actually look at the shopper's payment proof

> Completes roadmap item 2 ({@code stories/shopper_payment_proof_claim.md}). Branch
> `122_fix/staff-payment-proof-view`. Frontend pair: `frontst` branch `69_fix/staff-payment-proof-view`
> (story 72) — the backend can ship first; the field is additive and absent-when-null.
> No migration (V67 already added the column).

---

## The reality this corrects

The claim slice shipped the whole upload path — presign, direct PUT to object storage, prefix-guarded
attach, `payment_transaction.proof_object_key` persisted (V67) — and then **stopped**. Nothing ever
read the key back out. `PaymentTransactionResponse` carried `claimed_by_customer_id` but neither the
key nor a URL, and `PaymentTransactionService` never called `presignGet`. The only way for a merchant
to see a screenshot a shopper had sent was to open MinIO/S3 by hand with the key from the database.

So the feature was half-delivered in the worst direction: shoppers were asked for evidence, told it
had been received, and the person who had to act on it could not look at it. An operator deciding
whether to verify a claimed transfer was working from the typed reference alone — exactly the guess
the screenshot exists to remove.

This was **in the slice's own scope**: `shopper_payment_proof_claim.md` lists "the claim's
presigned-GET exposure on the transaction detail read so staff see the screenshot" among its
deliverables. The story says it shipped; the code says otherwise.

## The change

`GET /api/orgs/{orgId}/payment-transactions/{id}` (VIEWER, unchanged gating) gains **`proof_url`** —
a short-lived presigned GET, omitted entirely when the shopper attached nothing.

Three deliberate choices:

- **Detail only, never the list.** Presigning every row of a 100-item worklist page mints 100 read
  credentials the operator will never click, and a URL that grants bearer access has no business
  sitting in a paged response that may be cached or logged.
- **The URL, never the key.** The response carries something that expires. The stored object key —
  a stable handle that would keep working — stays server-side, consistent with how listing images
  and the org logo already cross this boundary.
- **`Cache-Control: private, no-store` on the detail.** The body now embeds a credential with a
  lifetime shorter than any cache's; a stored copy would hand out a link that has already died.

The read re-checks the stored key against this org's `{orgId}/payment-proof/` prefix before signing.
The write path already enforces the order-scoped form; this is the read refusing to mint a credential
for anything outside the tenant even if a bad key ever reached the column. The org-wide prefix (new
`ObjectStorage.paymentProofOrgPrefix`) is the right granularity here — the order-scoped form cannot
be used, because an ORPHAN transaction is bound to no order yet its proof is still the shopper's.

## Tests

`ShopperPaymentClaimIT`:
- `staffDetailRead_presignsTheShoppersProof_andTheListDoesNot` — the detail hands back a signed URL
  naming the uploaded file; the list row's DTO has no `proof_url`.
- `staffDetailRead_hasNoProofUrlWhenTheShopperAttachedNothing` — null, so a client branches on
  presence rather than rendering a broken image.

`PaymentTransactionService` gained a constructor parameter, which twelve ITs construct. Rather than
thread a presigner through twelve `@BeforeAll`/`@AfterAll` pairs, `TestWiring.storage()` provides one
shared instance: presigning is an offline signature computation, so there is nothing per-test about it.

## Definition of done

- [x] `mvn -o test -pl api` green (206 unit) and the full `*IT` battery green (811).
- [x] `mvn spotless:apply` clean.
- [x] `CLAUDE.md` transaction-detail entry updated (note: that file is gitignored here).
