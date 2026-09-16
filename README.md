# servlet-apis — a multi-tenant commerce & POS backend, framework-free Java

The API behind **yabta3**, a platform that gives small Egyptian retailers an online storefront,
a phone-first point of sale, money reconciliation for InstaPay / cash / card, invoicing, stock
control, a general ledger and a customer portal — all served to many tenant shops from one
PostgreSQL schema.

It is written in **Java 25 on raw Jakarta Servlets** (embedded Tomcat, no Spring, no DI
container), with **jOOQ** for type-safe SQL, **Flyway** migrations, **PostgreSQL 17**, **Redis**
and **JobRunr** for background work. It runs in production behind Caddy on a single VPS, deployed
on every merge to `master` by GitHub Actions.

The screenshots below come from the companion Next.js apps (admin, storefront, platform console,
a separate repository) driven entirely by this API, captured on a phone viewport against a
seeded demo tenant.

## At a glance

| | |
|---|---|
| Runtime | Java 25 · Jakarta Servlet 6.1 on embedded Tomcat 11 · manual dependency injection |
| Data | PostgreSQL 17 · jOOQ 3.19 (codegen from the live schema) · Flyway (102 migrations, 85 tables) · HikariCP |
| Infra | Redis (sessions, rate limits, OTPs, caches) · MinIO / S3 presigned uploads · JobRunr (6 recurring jobs) |
| Surface | 10 servlets · 46 resource handlers · ~170 routes across 4 planes (org, platform, public, customer portal) |
| Size | ~97k lines of main Java in 5 Maven modules · 142 story files documenting every slice |
| Tests | 2,249 test methods · 85 unit classes + 137 Testcontainers integration classes · run on every PR |
| Delivery | GitHub Actions → GHCR image → SSH deploy → Docker Compose behind Caddy (automatic HTTPS) |

## What it does

- **Storefront commerce** — public catalog with categories, collections, variants, facets, search
  and best-sellers; anonymous checkout with stock reservation and a per-tenant payment hold; honest
  coupons; wishlist; reviews and Q&A; sitemap / crawl feed / Open Graph images.
- **Point of sale** — barcode scan-to-cart, walk-in customers, counter discounts, split tender
  (cash + InstaPay), refund-from-receipt, cash shifts with derived expected cash, ESC/POS receipts
  rendered server-side as raster slips for Bluetooth thermal printers.
- **Order lifecycle** — `DRAFT → PENDING_PAYMENT → PAID → FULFILLING → FULFILLED → CLOSED`
  with cancellation, TTL expiry, partial-delivery cancel, failed-shipment replace / return / refund.
- **Money** — payment transactions with a verification queue for shopper-filed InstaPay claims,
  orphan resolution, disputes, refunds with an OWNER approval threshold measured on the aggregate
  payout, Paymob card checkout with HMAC-gated webhooks and an inquiry job for lost callbacks.
- **Invoicing** — invoices issued as a side effect of delivery or sale, void / reissue, credit
  notes, per-org per-year number counters with a forward-only reconciliation repair.
- **Stock** — reservations that keep `reserved_qty = Σ ACTIVE`, an append-only inventory ledger,
  stocktake sessions, reorder points that notify on the crossing, cost per unit frozen on every
  sale line, suppliers and goods receipts that write the cost actually paid.
- **General ledger** — a double-entry journal *derived* from the rows the domain already writes
  by one idempotent set-based poster (no money write site emits an entry), a trial balance and
  per-account statements.
- **Notifications** — one supertable, four channels (in-app, email, WhatsApp Cloud API, Web Push)
  with per-user opt-outs, localized templates, retry budgets, and magic links for customers.
- **Customer portal** — a second auth plane with its own signing key: email-OTP login, orders,
  invoices, addresses, reorder, sessions, notification preferences.
- **Platform console** — the tier above tenants: overview with an honesty contract (a failed
  section is `null` and named, never `0`), cross-org queues, search, tenant timeline, funnel and
  growth series, provisioning, suspension, audit ledger, a support desk, and audited two-tier
  impersonation.
- **Arabic-correct text** — ICU collation, generated folded search columns with trigram indexes,
  Arabic-Indic digit folding, E.164 phone normalization, bilingual content tables per entity.

## See it working

Phone viewports, 390 to 430 px wide. Admin and console screens are in English; the storefront and
portal are in Arabic because that is what the shoppers see (the same API serves both locales).

### Sell at the counter

Every screen here is `POST /sales-orders` with `channel=IN_STORE`, the `/shifts` resource, the
`GET /products?barcode=` lookup and the `adjust` / `restock` inventory actions.

<table>
<tr>
<td align="center"><img src="docs/screenshots/admin-sell.webp" width="260" alt="Point of sale with barcode scanner"><br><sub>Scan-to-cart POS, with the open shift's expected cash in the strip</sub></td>
<td align="center"><img src="docs/screenshots/admin-shift.webp" width="260" alt="Cash shift detail"><br><sub>Cash shift: float, pay-ins and pay-outs, expected cash derived not typed</sub></td>
<td align="center"><img src="docs/screenshots/admin-scan-to-stock.webp" width="260" alt="Scan to stock"><br><sub>Scan-to-stock: restock by pointing the camera at a barcode</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/admin-stocktake.webp" width="260" alt="Stocktake session"><br><sub>Stocktake: count by scanning, post the differences as one adjustment each</sub></td>
<td align="center"><img src="docs/screenshots/admin-launcher.webp" width="260" alt="App launcher"><br><sub>The launcher: every back-office surface this API serves</sub></td>
<td align="center"><img src="docs/screenshots/admin-notifications.webp" width="260" alt="In-app notifications"><br><sub>Staff feed: new orders, low stock, support replies</sub></td>
</tr>
</table>

### Run the day

The dashboard is one `GET /health` rollup plus the status-count reads. Every card is a queue, and
every list endpoint serves a queue oldest-first or a ledger newest-first by one convention.

<table>
<tr>
<td align="center"><img src="docs/screenshots/admin-home.webp" width="260" alt="Owner dashboard"><br><sub>Owner's dashboard: every queue, including the empty ones</sub></td>
<td align="center"><img src="docs/screenshots/admin-orders.webp" width="260" alt="Orders worklist"><br><sub>Orders worklist with status counts, search and filters</sub></td>
<td align="center"><img src="docs/screenshots/admin-order-detail.webp" width="260" alt="Order detail"><br><sub>Order detail: money, shipments, stock holds, the one next action</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/admin-fulfillments.webp" width="260" alt="Fulfillment queue"><br><sub>Packing and shipping queue</sub></td>
<td align="center"><img src="docs/screenshots/admin-support-thread.webp" width="260" alt="Support ticket thread"><br><sub>Support ticket thread with the platform desk's reply</sub></td>
<td align="center"><img src="docs/screenshots/admin-settings.webp" width="260" alt="Settings hub"><br><sub>Settings: organization, team, sessions, WhatsApp, card payments, printer</sub></td>
</tr>
</table>

### Money, reconciled

`/payment-transactions` is the reconciliation worklist: shopper claims to verify, orphans to
resolve or refund, disputes, and refunds executed in two steps after the real reverse transfer.

<table>
<tr>
<td align="center"><img src="docs/screenshots/admin-money.webp" width="260" alt="Money ledger"><br><sub>Money: the verification queue, the orphan queue, the transfer ledger</sub></td>
<td align="center"><img src="docs/screenshots/admin-transaction-detail.webp" width="260" alt="Transaction detail"><br><sub>An orphan transfer: match it to an order or refund it back</sub></td>
<td align="center"><img src="docs/screenshots/admin-refunds.webp" width="260" alt="Refunds queue"><br><sub>Refunds: pending queue, executed ledger</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/admin-invoices.webp" width="260" alt="Invoices worklist"><br><sub>Invoices: awaiting-payment queue with tab counts and paid meters</sub></td>
<td align="center"><img src="docs/screenshots/admin-invoice-detail.webp" width="260" alt="Invoice detail"><br><sub>Invoice detail: PDF, credit-note meter, void / reissue</sub></td>
<td align="center"><img src="docs/screenshots/admin-credit-note.webp" width="260" alt="Credit note"><br><sub>Credit note with its refund meter and thermal slip</sub></td>
</tr>
</table>

### Stock, cost and the books

<table>
<tr>
<td align="center"><img src="docs/screenshots/admin-inventory.webp" width="260" alt="Inventory overview"><br><sub>Inventory: available = on hand − reserved, low and out filters, cost coverage</sub></td>
<td align="center"><img src="docs/screenshots/admin-stock-ledger.webp" width="260" alt="Stock ledger statement"><br><sub>One product's stock statement: every movement with its reason and document</sub></td>
<td align="center"><img src="docs/screenshots/admin-receiving.webp" width="260" alt="Goods receipt"><br><sub>Goods receipt: the cost actually paid, written back to each product</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/admin-ledger.webp" width="260" alt="General ledger"><br><sub>General ledger trial balance, derived from the domain rows</sub></td>
<td align="center"><img src="docs/screenshots/admin-ledger-account.webp" width="260" alt="Ledger account statement"><br><sub>Account statement: every line is one leg of a balanced entry</sub></td>
<td align="center"><img src="docs/screenshots/admin-reports.webp" width="260" alt="Reports"><br><sub>Reports: orders, sales, profit and average sale against the previous window</sub></td>
</tr>
</table>

### Catalog and storefront management

<table>
<tr>
<td align="center"><img src="docs/screenshots/admin-catalog.webp" width="260" alt="Catalog listings"><br><sub>Listings: draft → published → archived, images via presigned uploads</sub></td>
<td align="center"><img src="docs/screenshots/admin-catalog-listing.webp" width="260" alt="Listing editor"><br><sub>Listing editor: bilingual copy, slug, categories, variants</sub></td>
<td align="center"><img src="docs/screenshots/admin-collections.webp" width="260" alt="Collections"><br><sub>Merchant-curated collections that become storefront chips</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/admin-coupons.webp" width="260" alt="Coupons"><br><sub>Honest coupons with live redemption counts</sub></td>
<td align="center"><img src="docs/screenshots/admin-storefront-settings.webp" width="260" alt="Storefront settings"><br><sub>Storefront settings hub: branding, sharing and SEO, banners, featured, pages</sub></td>
</tr>
</table>

### Platform console

The tier above tenants, for the platform's own operators (`ADMIN` / `SUPPORT`). Every read here
comes from a small, enumerated set of cross-org repositories with per-type field whitelists.

<table>
<tr>
<td align="center"><img src="docs/screenshots/console-overview.webp" width="260" alt="Platform overview"><br><sub>Overview: tenants, backlog queues, job health, build identity</sub></td>
<td align="center"><img src="docs/screenshots/console-queues.webp" width="260" alt="Cross-org queues"><br><sub>Cross-org queue drill-down, oldest first, suspended tenants included</sub></td>
<td align="center"><img src="docs/screenshots/console-funnel.webp" width="260" alt="Tenant funnel"><br><sub>Tenant lifecycle funnel over append-only milestones</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/console-growth.webp" width="260" alt="Growth series"><br><sub>Growth: arrivals per stage per week, counts only</sub></td>
<td align="center"><img src="docs/screenshots/console-org-detail.webp" width="260" alt="Tenant detail"><br><sub>Tenant detail: health, policy knobs, owners, suspend / reactivate</sub></td>
<td align="center"><img src="docs/screenshots/console-ticket.webp" width="260" alt="Support desk"><br><sub>Support desk: the merchant's ticket, answered</sub></td>
</tr>
</table>

### Storefront and customer portal

Anonymous, rate-limited, cache-controlled reads under `/api/public`; a separate customer auth
plane under `/api/portal`. Shown in Arabic, right-to-left.

<table>
<tr>
<td align="center"><img src="docs/screenshots/store-home.webp" width="260" alt="Storefront home"><br><sub>Storefront home: collections, categories, featured strip</sub></td>
<td align="center"><img src="docs/screenshots/store-product.webp" width="260" alt="Product page"><br><sub>Product page with rating, availability and quantity</sub></td>
<td align="center"><img src="docs/screenshots/store-reviews.webp" width="260" alt="Reviews"><br><sub>Verified-purchase reviews, written from the portal, moderated in the admin</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/store-search.webp" width="260" alt="Search results"><br><sub>Search with sort, price band and category facets</sub></td>
<td align="center"><img src="docs/screenshots/store-checkout.webp" width="260" alt="Checkout"><br><sub>Anonymous checkout: reserves stock and starts the payment hold</sub></td>
<td align="center"><img src="docs/screenshots/store-pay-instructions.webp" width="260" alt="Payment instructions"><br><sub>InstaPay instructions, the countdown, and the shopper's "I paid" claim</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/portal-otp-login.webp" width="260" alt="Portal login"><br><sub>Customer portal: email-OTP login, no password</sub></td>
<td align="center"><img src="docs/screenshots/portal-account.webp" width="260" alt="Customer account"><br><sub>Customer account: orders, invoices, addresses, reviews, wishlist, sessions</sub></td>
<td align="center"><img src="docs/screenshots/portal-orders.webp" width="260" alt="Customer orders"><br><sub>Customer order history with live statuses</sub></td>
</tr>
</table>

## Architecture

Five Maven modules with strict top-down dependencies:

```
api  →  service  →  repository  →  domain
                        ↓
                      common
```

| Module | Holds |
|---|---|
| `domain` | Pure models, enums (the state machines), repository interfaces. Zero dependencies. |
| `common` | HikariCP data source, exception hierarchy (each carries its HTTP status), text normalization, `SecretBox` |
| `repository` | jOOQ implementations, Flyway migrations, generated types (not committed, regenerated from the live schema) |
| `service` | Business rules, transaction boundaries, notification production, PDF / ESC-POS rendering, PSP clients |
| `api` | Servlets, per-resource handlers, filters, DTOs, JobRunr jobs, the composition root |

A request goes **`CorsFilter` → `RateLimitFilter` → `JwtAuthFilter` / `CustomerAuthFilter` →
servlet → handler → service → `UnitOfWork` → jOOQ**. Tomcat cannot route on `{orgId}`, so one
servlet per plane parses the path and dispatches to a handler class per resource.

### Design rules that hold everywhere

- **No framework, one composition root.** `AppConfig` constructs and wires every dependency by
  hand. There are no annotations to scan and nothing happens at a distance.
- **Tenancy is a parameter, not a thread-local.** Every business table carries `org_id`, and
  every service and repository method takes `orgId` first. The servlet layer validates it against
  the token's roles before any service call. The few cross-org reads that exist live in five
  enumerated platform repositories that are read-only, platform-gated, and PII-whitelisted.
- **The service owns the transaction.** `UnitOfWork.execute(repos -> …)` hands the service a set
  of repositories bound to one connection; services import nothing from jOOQ.
- **State machines are enums, transitions are methods.** Orders, payments, invoices, fulfillments,
  refunds, credit notes, tickets and receipts each have one status enum and a service method per
  transition; illegal transitions are `409`s that name the remedy.
- **Queue vs ledger.** A filtered list is a queue (oldest first); an unfiltered one is a ledger
  (newest first). Every paged read follows it, and the handful that deviate say why in the code.
- **Numbers are minted under lock.** Order, invoice, credit-note and receipt numbers come from
  per-org per-year counters advanced `FOR UPDATE` inside the business transaction, so a rollback
  burns nothing.
- **Idempotency keys on money and stock.** Order placement on every channel, counter returns,
  inventory movements, goods receipts and both checkouts require an `Idempotency-Key`; a replay
  returns the original result, a reuse with a different body is a `409`.
- **Honesty over convenience.** A dashboard section that fails to compute is `null` and named in
  `degraded[]`, never `0`. Report series are sparse: the API never zero-fills a bucket it did not
  measure. Counts cross the wire, percentages never do.

### The two auth planes

| | Staff & platform (`/api/auth`, `/api/orgs`, `/api/admin`) | Customers (`/api/portal`) |
|---|---|---|
| Identity | `app_user` with org roles `OWNER > MANAGER > STAFF` and system roles `ADMIN` / `SUPPORT` | `customer` (a CRM record) proven by email OTP |
| Tokens | Short JWT access cookie + rotating refresh families in Redis | Same shape, **different signing key**, enforced distinct at startup |
| Refresh theft | Proven reuse burns the whole family; a 10-second grace window separates theft from a benign double-refresh | Same mechanism (`crt:*` keys) |
| Lockout | 10 failures on one address lock it 15 min with a live `Retry-After`; unknown addresses pay a dummy bcrypt | Per-IP and per-email OTP throttles |
| Act-as | Two-tier audited impersonation (platform → tenant, owner → subordinate) with `read_only` for SUPPORT | — |

Everything under `/api/public` and `/api/psp` bypasses JWT: it is rate-limited per IP by named
Redis buckets, cache-controlled, and (for the PSP) HMAC-gated. Per-org PSP secrets are sealed at
rest with `SecretBox` under a key the server refuses to start without when card payments are on.

### Data model highlights

- One schema, ~85 tables, every migration in `repository/src/main/resources/db/migration`.
- `product` (internal: SKU, barcode, cost) and `product_listing` (public: title, copy, price,
  images) are separate aggregates joined by a foreign key, so no query bug can leak a margin.
  Variants are child products bridged by `product_variant`, so per-variant stock reuses every
  existing inventory path.
- Bilingual content lives in `*_translation` tables keyed `(parent_id, language)`; `name_search`
  columns are generated through a `fold_search` function with `und-x-icu` collation and trigram
  indexes, so `احمد` finds `أحمد`.
- `CHECK` constraints and partial uniques encode the invariants (one live invoice per
  fulfillment, `reserved_qty = Σ ACTIVE`, reservations never exceed stock). The seed harness once
  produced inconsistent data and the schema rejected it, which is the point.
- Indexes are added only with a before-measurement at production scale. The one that shipped as a
  flat is documented as a flat. See the case study below.

### Background jobs (JobRunr, schema vendored under Flyway)

| Job | Does |
|---|---|
| `OrderTtlSweeperJob` | Expires unpaid orders past their per-org hold and releases their reservations, one transaction per order |
| `NotificationDeliverySweeperJob` | Drains the four delivery channels with retry budgets and claim leases |
| `LedgerPosterJob` | Posts any journal entries the domain rows imply but the ledger lacks (delta-first, idempotent) |
| `PaymobInquiryJob` | Reconciles card intents whose webhook never arrived |
| `SupportTicketAutoCloseJob` | Closes resolved tickets the merchant stopped answering |
| `UnverifiedAccountPurgeJob` | Deletes never-verified self-serve accounts after the grace period |

The platform overview reads their health off JobRunr's own tables and reports `STALE` against each
job's configured period, so re-tuning an interval cannot fake a heartbeat.

### Integrations

SMTP (Angus Mail) · WhatsApp Cloud API templates · Web Push (RFC 8292 VAPID, keys verified at
boot) · Paymob card checkout · S3-compatible object storage via presigned PUT/GET (MinIO in dev)
· OpenPDF invoices, credit notes and receipts · Java2D-rastered ESC/POS slips · dnsjava MX checks
for the email quality gate.

## Performance, measured

[`docs/performance-case-study.md`](docs/performance-case-study.md) walks through auditing every
read path, seeding **16.3 million rows** of state-machine-consistent data (145k real products,
1M orders, 5M ledger rows) into a schema-cloned benchmark database, and measuring both the query
plan and the full HTTP stack before and after each index.

| | before | after |
|---|---|---|
| Worst query (order lines by order id) | 344 ms | 0.018 ms |
| Worst user-felt endpoint p50 | 127.6 ms | 5.1 ms |
| Payments status worklist p50 | 75.3 ms | 6.4 ms |

![Query latency before and after indexing, log scale](docs/img/query-latency-before-after.svg)

The audit (`docs/query-index-audit.md`), the seed harness (`tools/seed/`), the benchmark client
(`tools/bench/`) and every raw `EXPLAIN` capture (`tools/seed/results/`) are in the repository.
Later slices kept the rule: each new read ships with its plan at scale, and several proposed
indexes were measured and dropped.

## API conventions

- Routes are plane-prefixed: `/api/auth/*`, `/api/me/*`, `/api/orgs/{orgId}/*`, `/api/admin/*`,
  `/api/public/{orgSlug}/*`, `/api/portal/*`, `/api/psp/*`.
- JSON is `snake_case`, dates are ISO-8601, nulls are omitted, unknown request fields are ignored.
- Lists return `{data, total, page, size}`; unknown filter values are `400`s that name the valid
  set; a path segment that names nothing is a `404`, a query parameter that matches nothing is an
  empty page.
- Errors are a JSON envelope with the HTTP status and a message; conflicts carry a `kind`
  discriminator (`CLAIM_PENDING`, `TICKET_CLOSED`, …) so clients branch on a code, not on prose.
- Mutations that move money or stock take an `Idempotency-Key` header.
- Authorization is layered: the org role ladder for tenant routes, system roles for the platform,
  the customer plane for the portal, and a single `AuthzHelper` that every handler calls.

The full per-endpoint contract, including the reasoning behind each status code, is kept beside
the code in `stories/` (one file per slice, 142 of them).

## Testing and CI

- **Unit tests** (JUnit 5 + Mockito) for services, text normalization, crypto and DTO mapping.
- **Integration tests** (Testcontainers PostgreSQL) for every repository and every servlet
  plane: they boot the real migrations, the real filters and the real transaction boundaries.
  Security properties are pinned as tests (no customer PII on the platform search, the two signing
  keys must differ, a rotated refresh token burns its family).
- **CI** runs `mvn -Pcodegen clean verify` on every pull request against a PostgreSQL service
  container: Flyway migrate, jOOQ codegen, compile, unit tests, integration tests, Spotless.

```bash
mvn test                                           # everything
mvn test -pl service -Dtest=ReportServiceTest   # one class
```

## Running locally

```bash
docker compose up -d                                   # PostgreSQL :5433, Redis :6379, MinIO :9100
mvn generate-sources -Pcodegen -pl repository          # Flyway migrate + jOOQ codegen from the live schema
mvn install -DskipTests

export JWT_SECRET=$(openssl rand -base64 48)
export CUSTOMER_JWT_SECRET=$(openssl rand -base64 48)  # must differ from JWT_SECRET
mvn exec:java -pl api                                  # http://localhost:8080
```

Email, WhatsApp, Web Push and card payments each fall back to a logging sender when their
credentials are unset, so a dev machine needs no external accounts. The complete list of
environment variables, with defaults, is in `deploy/env.prod.example`.

`mvn clean` deletes the generated jOOQ sources; regenerate after a clean or after any migration.

## Deployment

```
push master ─▶ GitHub Actions ─▶ ghcr.io/…/inventory-api ─▶ VPS: docker compose up -d backend
              (codegen + tests +           │
               runtime image)              └─ Caddy (80/443, Let's Encrypt)
                                               ├ api.<domain>    → backend :8080
                                               ├ files.<domain>  → MinIO
                                               └ admin / store / apex → the frontend images
```

The image is runtime-only (`eclipse-temurin:25-jre`, unprivileged user): the classpath is built
in CI because jOOQ generates its Java from a live, migrated database. Migrations run on startup.
Rollback is `BACKEND_IMAGE=…:<sha>` and a compose restart. Nightly `pg_dump` with retention runs
from cron. Details in [`deploy/README.md`](deploy/README.md).

## Repository layout

```
api/            servlets, handlers, filters, DTOs, jobs, AppConfig (composition root), Dockerfile inputs
service/        business services, auth, notifications, documents, paymob, whatsapp, push
repository/     jOOQ repositories, Flyway migrations (V1…V102)
domain/         models, enums, repository ports
common/         data source, exceptions, text normalization, SecretBox
stories/        one design + contract document per shipped slice
docs/           architecture docs, the performance case study, plans
tools/seed/     production-scale seed harness + measurement captures
tools/bench/    HTTP benchmark client
deploy/         Compose stack, Caddyfile, env template, backup script
.github/        CI (PRs) and deploy (master) workflows
```

## Reading guide

| Start here | Then |
|---|---|
| [`docs/performance-case-study.md`](docs/performance-case-study.md) | [`docs/query-index-audit.md`](docs/query-index-audit.md) |
| [`docs/catalog-architecture.md`](docs/catalog-architecture.md) | [`docs/catalog-variants-architecture.md`](docs/catalog-variants-architecture.md) |
| [`docs/impersonation.md`](docs/impersonation.md) | [`docs/platform-admin-plan.md`](docs/platform-admin-plan.md) |
| [`docs/notifications-plan.md`](docs/notifications-plan.md) | [`stories/web_push_channel.md`](stories/web_push_channel.md) |
| [`stories/general_ledger.md`](stories/general_ledger.md) | [`stories/supplier_goods_receipt.md`](stories/supplier_goods_receipt.md) |
| [`stories/cash_shift.md`](stories/cash_shift.md) | [`stories/escpos_receipt.md`](stories/escpos_receipt.md) |
| [`stories/payment_claim_verify.md`](stories/payment_claim_verify.md) | [`stories/paymob_card_checkout.md`](stories/paymob_card_checkout.md) |
| [`docs/unicode-normalization.md`](docs/unicode-normalization.md) | [`stories/phone_e164_normalization.md`](stories/phone_e164_normalization.md) |

## License

[MIT](LICENSE)
