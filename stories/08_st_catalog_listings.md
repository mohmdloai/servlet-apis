# 08 — Story: Catalog — product listings (status tabs, the desktop editor, presigned image upload)

> The authoring slice. Every prior slice **consumed** the catalog — orders reference products,
> inventory tracks them, fulfillment ships them — but nobody in the admin has ever been able to
> create the storefront face of a product. This slice is setup-time work: the **ProductListing**
> with its DRAFT → PUBLISHED → ARCHIVED publish lifecycle, its marketing copy and sales price, its
> category assignment, and real image upload via presigned object-storage URLs. It is the
> deliberately **desktop/tablet-leaning** slice — the editor, the image manager, and the category
> picker are rich forms that earn a genuine multi-column layout — but it still lives inside the
> mobile-first responsive shell (a manager must be able to publish or unpublish a listing from a
> phone). Branch `08_feat/catalog-listings`, PR #8 (verified via `gh pr list`/`gh issue list`: PRs
> 1–7 exist and are merged, no issues share the sequence).
>
> **Two aggregates, never confused.** The backend has a lean internal **Product** (name /
> description / base_price / sku — the ERP record; no status, no images) and a rich storefront
> **ProductListing** (title / marketing_copy / slug / sales_price + status + categories + images),
> one listing per product. This slice is the **ProductListing** API. The internal Product is the
> prerequisite a listing points at (`product_id`) and a thin supporting surface, not the star.
>
> Grounded in backend **code** (docs lag): `ProductListingHandler`/`CategoryHandler`/`ProductHandler`,
> `ProductListingService`/`CategoryService`/`ProductService`, `ObjectStorage` + `storage.properties`,
> and the domain models `ProductListing`/`ListingStatus`/`ProductListingImage`/`Category`/`Product`.
> The full catalog contract shipped with the backend ProductListing/Category slice (migrations
> V39/V40) — nothing needs building on the backend first; this slice designs directly against it.
> Builds on the story-02 shell (the Stock tab family already maps `/catalog/*`), the story-04/05
> paginated-worklist + status-tab patterns, the story-06/07 in-flight/outcome/calm-409 patterns, and
> `frontend-architecture.md` §3.1 (the listing machine, quoted verbatim), §3.7 (the presigned upload
> flow), §5 (in-flight, never optimistic), §6 (spoking UX), §7 (cross-cutting), §8 (DoD).

**As** the person who sets up the store — a new product just landed and needs a storefront page, or a
seasonal line needs pulling down, or a price and photo need fixing —
**I want** a catalog of listings I can filter by publish state, a real editor with room to write
marketing copy, assign categories, upload photos, and set a price, and deliberate publish / unpublish
/ archive controls that only ever offer the transition that's actually legal from here —
**so that** I can author the storefront from a laptop with the space it deserves, still publish or
unpublish from my phone, and never be shown an action the server would reject.

---

## The person and the moment

This is not the packing-queue persona. Catalog work is **sit-down, considered, setup-time** work: you
open it on a laptop or a tablet, not one-handed on the floor. So the editor is the one screen in this
product that earns a real two-column layout — marketing copy on the left with room to breathe, the
images / categories / publish rail on the right — collapsing to a single honest column on a phone when
you *do* need to unpublish something from the bus.

The loop: from the **catalog** you see listings grouped by state — **Draft** (work in progress),
**Published** (live on the storefront), **Archived** (retired), and **All**. You start a new listing,
pick the internal product it represents (or create that product inline if it's brand new), write its
title and let the slug fill itself from it, add marketing copy and a sales price, tick the categories
it belongs to, drag no photos anywhere — you **upload** them, watching the bytes go straight to
storage with a real progress bar — and when it looks right you **Publish**. Later you **Unpublish** to
tweak, or **Archive** to retire it for good (a one-way door, so we ask you to mean it). Every one of
those verbs appears only when the listing's current state allows it; try a stale one and the screen
calmly refreshes to the truth instead of throwing an error wall.

The copy beat worth getting right: **archiving is final** — there is no un-archive in the backend, so
the UI must never imply one. Archive is a deliberate, confirmed, one-way action, and an archived
listing's rail shows only what an archived listing can still do (nothing but exist).

---

## Backend contract (verified in code, 2026-07-05)

All under `/api/orgs/{orgId}/…` via the `/api` rewrite. JSON **snake_case**, ISO-8601 UTC. Error
envelope `{status, error, message}` — **no machine `error_code`**, so (as in stories 06/07) errors map
on **HTTP status + calling context**, never a code. `PageResponse {data, total, page, size}` on both
list endpoints; `page` default 0, `size` default 10, clamped to `[1,100]` (400 `"page must be >= 0"` /
`"size must be 1-100"` / `"page is out of range"`; non-integer param → 400 `"Parameter '<name>' must be
an integer"`). Roles: **VIEWER** read / **STAFF** write + lifecycle + images + categories / **MANAGER**
delete (system ADMIN bypasses). Cross-org access is a 404. Auth: 401 `"Authentication required"`;
insufficient role → 403 `"Requires at least <ROLE> role in org <id>"`.

### THE price-serialization answer (pinned, do not re-litigate)

`sales_price` and internal `base_price` are `NUMERIC(12,2)` → `java.math.BigDecimal`. The single shared
`ObjectMapperProvider` configures **only** JavaTimeModule + `WRITE_DATES_AS_TIMESTAMPS=off` +
`SNAKE_CASE` + `NON_NULL` + `FAIL_ON_UNKNOWN_PROPERTIES=off`. There is **no** `WRITE_BIGDECIMAL_AS_PLAIN`,
**no** `ToStringSerializer`, **no** `@JsonFormat` on any price field → Jackson emits `BigDecimal` as a
bare **JSON number**, preserving scale-2: `"sales_price": 200.00`, `"base_price": 49.50` — **never a
quoted string**. (The verified answer to the brief's open question.) The app is integer-piastres
end-to-end (`Money`, §3.4); the mapper converts at the edge with `moneyFromEgp`, which already tolerates
`number | string`. We type the DTO field `number | string` for resilience (matching the `refund`
precedent) and **pin the mapper with a unit test** asserting a JSON number maps correctly — a backend
switch to strings could then never silently corrupt a price.

`NON_NULL` means **null fields are omitted entirely** (not sent as `null`): `marketing_copy`,
`published_at`, `alt_text`, `parent_category_id`, and — on the **lean** list rows — `category_ids` and
`images` are simply absent. Map defensively (`?? null`).

### Product listings — the star (`/product-listings`)

| Verb + path | Role | Body / params | Returns |
|---|---|---|---|
| `GET /product-listings?status=&page=&size=` | VIEWER | `status ∈ DRAFT\|PUBLISHED\|ARCHIVED` (case-insensitive; absent = all); unknown → 400 `"status must be one of DRAFT, PUBLISHED, ARCHIVED (was '<x>')"` | `PageResponse` of **lean** rows |
| `GET /product-listings/{id}` | VIEWER | — | **rich** detail: listing + `category_ids[]` + `images[]` |
| `POST /product-listings` | STAFF | `{product_id, title, marketing_copy?, slug, sales_price}` | 201 lean (status defaults DRAFT, published_at null) |
| `PUT /product-listings/{id}` | STAFF | `{title, marketing_copy?, slug, sales_price}` (no product_id — immutable) | 200 lean |
| `DELETE /product-listings/{id}` | **MANAGER** | — | 204 (hard delete; cascades categories + images) |
| `POST /product-listings/{id}/publish` | STAFF | — | 200; 409 `"Only a DRAFT listing can be published (was <status>)"` |
| `POST /product-listings/{id}/unpublish` | STAFF | — | 200; 409 `"Only a PUBLISHED listing can be unpublished (was <status>)"` |
| `POST /product-listings/{id}/archive` | STAFF | — | 200; 409 `"Listing is already ARCHIVED"` |
| `GET /product-listings/{id}/categories` | VIEWER | — | `[uuid]` (assigned category ids) |
| `PUT /product-listings/{id}/categories` | STAFF | `{category_ids: [uuid]}` (whole-set replace; empty/null clears) | `[uuid]`; 400 `"One or more category ids do not exist in this org"` |

`ProductListingResponse` (lean): `id, org_id, product_id, title, marketing_copy?, slug, sales_price,
status, published_at?, created_at, updated_at`. Rich adds `category_ids: [uuid]`, `images:
[ProductListingImage]`. Create/update guards (400): `"title is required"`, `"slug is required"`,
`"sales_price is required"`, `"sales_price must be >= 0"`, `"product_id is required"`, and (create only)
`"product_id not found in this org"`; POST/PUT with an empty body → 400 `"request body is required"`.
Create conflicts (409): `"This product already has a listing"` (one listing per product), `"Listing slug
already used in this org: <slug>"`; update slug clash → the same 409.

### Lifecycle state machine (binds the status tabs + `availableActions()`)

`DRAFT ⇄ PUBLISHED → ARCHIVED` (ARCHIVED **terminal**). publish DRAFT→PUBLISHED (stamps
`published_at`); unpublish PUBLISHED→DRAFT (clears `published_at`); archive from any non-archived state
→ ARCHIVED (leaves `published_at` as-is). **There is no un-archive.** Every illegal transition is a 409
with the exact string above — rendered as a calm "refreshed — that action isn't available from this
state" surface after `router.refresh()`, never an error wall. This is the canonical small example from
`frontend-architecture.md` §3.1 (the `productListingMachine` there is quoted verbatim in
`entities/productListing`).

### Categories — full CRUD, hierarchical (`/categories`, the editor's category source)

| Verb + path | Role | Notes |
|---|---|---|
| `GET /categories?page=&size=` | VIEWER | `PageResponse` of `CategoryResponse` |
| `GET /categories/{id}` | VIEWER | one |
| `POST /categories` | STAFF | `{name, slug, parent_category_id?}` → 201 |
| `PUT /categories/{id}` | STAFF | same body |
| `DELETE /categories/{id}` | **MANAGER** | 409 `"Cannot delete a category that still has subcategories"` |

`CategoryResponse`: `id, org_id, parent_category_id?, name, slug, created_at, updated_at` — a tree via
`parent_category_id`. Guards (400): `"name is required"` / `"slug is required"` / `"A category cannot be
its own parent"` / `"parent_category_id not found in this org"` / `"parent_category_id would create a
cycle"` / `"category hierarchy too deep"`; 409 `"Category slug already used in this org: <slug>"`. The
editor's picker is backed by `GET /categories` (fetch the flat list, present it hierarchically);
assigning is the whole-set `PUT /product-listings/{id}/categories`.

### Images — presigned object storage, three-step upload (§3.7)

Bytes **never flow through the API**. All STAFF except the read:

1. `POST /product-listings/{id}/images/presign {filename, content_type}` → 200 `{upload_url, object_key,
   expires_in_seconds}`. No DB row yet. Guard: 400 `"filename is required"`.
2. Client **PUTs the raw bytes directly to `upload_url`** (MinIO in dev / S3 in prod) — a plain
   `PUT` with `Content-Type`, **no auth header, not through `/api`**. This is the one place the app
   talks to a non-API origin (§3.7). We use **XHR** (not fetch) so we get real upload-progress events —
   zero new deps.
3. `POST /product-listings/{id}/images {object_key, alt_text?, sort_order?}` → 201 `{id, url, alt_text,
   sort_order}`. Guards: 400 `"object_key is required"` / `"object_key does not belong to this listing"`
   — the key must be one this listing minted (prefix `{orgId}/listings/{listingId}/`); never hand-craft
   keys, always use step 1's.
4. `GET /product-listings/{id}/images` (VIEWER) → `[{id, url, alt_text, sort_order}]`, each `url` a
   **freshly presigned GET URL that expires** (`storage.presign-ttl-seconds` = **900s / 15 min**, shared
   by PUT and GET). Never persist or long-cache these; refetch on view; a broken image reads as "URL
   expired — refresh".
5. `DELETE /product-listings/{id}/images/{imageId}` (STAFF) → 204.

`expires_in_seconds` = 900. Object-key format `{orgId}/listings/{listingId}/{uuid}-{sanitizedName}`.

### Internal Product — the thin prerequisite (`/products`)

`GET` list/one VIEWER, `POST`/`PUT` STAFF, `DELETE`. `ProductResponse`: `id, org_id, name,
description?, base_price, sku` (+ timestamps). Create guards (400): `"name is required"`, `"basePrice
must be >= 0"` (note: camelCase message, unlike the listing's snake_case `sales_price`), `"sku is
required"`; 409 `"SKU already exists: <sku>"`. `barcode` exists in the DB but is unwired — **do not
surface it**.

### Backend gaps / constraints we design around (do not paper over)

1. **List rows are lean** — no `images`, no `category_ids` (populated on the single-detail read only).
   So a catalog grid **cannot** show thumbnails or a category count from the list endpoint. **Chosen
   (a): text-first cards** — title, status chip, `<Money>` sales price, slug, updated-at — with imagery
   and categories appearing on the detail/editor. Honest and simplest. We do **not** invent an image or
   category field on the list response, and (correcting the brief's opinionated seed) we do **not** show
   a "category count" on cards — it isn't in the lean row. A backend `primary_image_url` on list rows is
   the clean future fix (Gap G1), noted not built.
2. **Images are set-once** — no reorder, no alt-text edit, no PATCH/PUT on an image. `sort_order` and
   `alt_text` are fixed at attach time. The manager therefore sets order + alt **at upload**, and any
   "change" is **remove + re-add**. No drag-to-reorder affordance that can't persist — the UI never
   implies a persistence that isn't there (Gap G2).
3. **Price serialization** — resolved above (JSON number, scale-2). Pinned by a mapper unit test.
4. **A listing needs an existing `product_id`** — create 400s if the product isn't in the org, and 409s
   if it already has a listing (one-per-product). The create flow lets the user **pick an existing
   product or create one inline**. Caveat: internal-product **DELETE has no handler role check**
   (`ProductHandler.doDelete` omits `requireOrgAccess` — a backend bug, Gap G3) **and** no referential
   guard (deleting a product referenced by orders/inventory/a listing raises a raw 500, not a clean
   409). So we **do not surface internal-product delete at all** in this slice — it's dangerous and out
   of scope; the create-product path is inline-create only.

---

## The design — screen by screen

### Navigation map

```
Stock tab family (nav.ts already maps catalog → 'stock'; the entry exists from story 02).
The Stock bottom tab lights up on /catalog/*; catalog is reached from the More sheet and the md+
sidebar (both already render the catalog entry). No nav.ts change is required.

/{orgId}/catalog                    the catalog — URL-driven status tabs:
   Draft · Published · Archived · All   (?status=)
   ├─ listing card ──────▶ /{orgId}/catalog/{id}      [the editor]
   ├─ per-card actions ── publish / unpublish / archive / edit (availableActions())
   ├─ "New listing" ─────▶ /{orgId}/catalog/new       [the editor, create mode]
   └─ "Manage categories" ▶ /{orgId}/catalog/categories

/{orgId}/catalog/new  and  /{orgId}/catalog/{id}     the listing editor (desktop-leaning):
   md+  two columns — content (left) · images + categories + status rail (right)
   < md single honest column, the status rail docked as a StickyActionBar
   ├─ identity — the linked internal product (name/SKU read-only); create-product inline when new
   ├─ storefront content — title, slug (slug-from-title helper), marketing copy, <Money> sales_price
   ├─ categories — tree-aware multi-select from GET /categories, saved via the whole-set PUT
   ├─ image manager — presign→PUT→attach with real progress; gallery; remove = DELETE + confirm
   └─ lifecycle rail — the state machine with the legal action(s) only; archive = one-way confirm

/{orgId}/catalog/categories          category manager (small supporting surface):
   the tree (create/edit/delete with a parent picker); delete-with-children 409 as a designed state
```

`/catalog/{id}`, `/catalog/new`, and `/catalog/categories` are all detail paths (`isDetailPath` → 2+
segments): phone back affordance, tab bar yields to the record's sticky bar.

### 1 · Catalog list (`/{orgId}/catalog`)

- Story-05 worklist pattern: **card feed below `md`, dense table at `md+`**, URL-driven status tabs via
  `Worklist.Tabs`, `WorklistRefresh` pull-to-refresh, shape-matched `WorklistSkeleton`, designed
  per-tab `Worklist.Empty` ("No drafts — start a new listing").
- Tabs: **Draft · Published · Archived · All** → `?status=` (`DRAFT`/`PUBLISHED`/`ARCHIVED`/absent).
  `PAGE_SIZE = 20`, prev/next `PageLink` pagination on the server-truth `total` (a catalog, not an
  infinite queue).
- **Search is not a backend param** here (`GET /product-listings` takes only `status/page/size`). We
  **omit server search** and say so — no fake search box. (A client-side filter of the current page
  would be dishonest about paging, so it's out; org-wide listing search is a future backend param.)
- Card: **title**, a `StatusBadge` (via the machine's `intents`), `<Money>` sales price, the slug
  (`dir="ltr"`), `<DateTime updated_at relative>`, and a per-card `availableActions()` row (edit +
  the legal lifecycle verb). No thumbnail, no category count (Gap G1 — not in the lean row).
- Header actions: **New listing** (→ `/catalog/new`, STAFF+) and **Manage categories** (→
  `/catalog/categories`).

### 2 · Listing editor (`/{orgId}/catalog/[id]` and `/new`) — the desktop-leaning centerpiece

- **Layout**: a real `md:grid md:grid-cols-[1fr_20rem]` — content left, a rail (images, categories,
  lifecycle) right; single column below `md`, with the lifecycle actions docking to a
  `StickyActionBar`. Logical CSS throughout so the two columns mirror correctly in RTL.
- **Identity**: the linked internal **Product** shown read-only (name + SKU). In **create** mode a
  product picker (existing products) with an inline **Create product** path (name / description /
  base_price / sku — no barcode); on save, create → get `product_id` → create listing. Choosing a
  product that already has a listing surfaces the create 409 calmly ("This product already has a
  listing").
- **Storefront content**: `title`; `slug` with a **slug-from-title helper** (auto-fills while the user
  hasn't hand-edited the slug; a pure `slugify` lib fn, unit-tested); `marketing_copy` (textarea);
  `<Money>`-edged `sales_price` via `parseEgpInput` (integer piastres, never a float). RHF + Zod
  mirroring the backend guards; the slug/one-listing 409s map to field errors.
- **Category assignment**: the `category-picker` — `buildCategoryTree` from the flat `GET /categories`,
  rendered as an indented checkbox tree; save is the whole-set `PUT` (unknown-id 400 → calm inline).
- **Image manager**: see §3.
- **Lifecycle rail**: `listing-actions` — an `availableActions(productListingMachine, status, role)`
  bar (publish / unpublish / archive), the state shown as a `StatusBadge`. Archive → a one-way-finality
  `ConfirmAction`. Every 409 → `router.refresh()` + a calm "not available from this state" line.
- **Unsaved-changes guard**: an in-editor dirty check warns before navigating away with unsaved content
  edits. **In-flight saves, never optimistic**; `router.refresh()` for server truth on every mutation.

### 3 · Image manager (`listing-image-manager`)

- The three-step flow (§3.7) as one `uploadListingImage` orchestration: **presign → PUT (XHR, real
  progress %) → attach**. `alt_text` and `sort_order` are captured **at add time** (there is no edit
  later). Progress is the direct-to-storage PUT's `upload` progress event (0–100%).
- **Gallery** of current images from `GET .../images` — fresh presigned GET urls, **refetched on load**
  (never cached beyond TTL); a broken image shows a "refresh" affordance, not a dead icon.
- **Remove** = `DELETE .../images/{imageId}` behind a `ConfirmAction`. Because there is no
  reorder/patch (Gap G2), the UI offers **no drag-to-reorder and no alt/position edit** — "change
  order / fix alt" is explicitly *remove and re-add*, stated in copy. `sort_order` is a number input at
  upload; the gallery renders in `sort_order` then `id`.
- **Create mode**: images and categories need a listing id, so in `/new` the image + category sections
  are disabled with "Save the listing first, then add photos and categories" until the listing exists
  (create is a two-phase save: content first → then the rail unlocks). Editing an existing listing has
  everything live.

### 4 · Lifecycle actions (card + editor rail)

`publish` / `unpublish` / `archive` are `availableActions()`-driven on **both** the card and the editor
rail — same pure brain (§3.2), no state/role literal in JSX (grep-provable). VIEWER sees none; STAFF+
sees the one legal verb. Archive gets the one-way-finality confirm. A replayed/stale click → the exact
409 → calm refresh.

### 5 · Category management (`/{orgId}/catalog/categories`)

A lightweight `category-manager`: the tree (`buildCategoryTree`), create/edit with a **parent picker**
(the create/update body's `parent_category_id`), delete (MANAGER) with the **delete-with-children 409**
as a designed calm state, and the cycle/depth/self-parent/slug guards mapped to inline messages. It
feeds the editor's picker; kept deliberately small.

### 6 · Roles (grep-provable, no literals)

VIEWER read-only; STAFF create / edit / lifecycle / images / categories; MANAGER additionally delete
(listing + category). The lifecycle bar uses `availableActions()` + `useOrgRole`; non-lifecycle write
affordances (New listing, Save, upload, category CRUD, delete) gate via `RoleGate`/`useOrgRole` — no
role string in JSX.

---

## Scope

### In

- `packages/entities/productListing` **completed** from its stub: keep the verbatim `productListingMachine`
  (§3.1); add the `ProductListing` model (Money `salesPrice`) + `ListingImage` model + `toProductListing`
  / `toListingImage` mappers (price tolerant of `number | string`, pinned by test); the presign/attach
  request types (already stubbed); `listingActions` = the machine's `availableActions` (per-role, no
  un-archive) exercised by a truth-table test; `listingKeys`. Set the machine `labelKey`s to the
  `catalog.actions.*` namespace.
- `packages/entities/category` **completed**: `Category` model + `toCategory` mapper; keep
  `buildCategoryTree` (+ tests); a `categoryActions(role)` (delete = MANAGER) helper; `categoryKeys`.
- `packages/entities/product` **new (thin)**: `ProductDTO`/`Product` (name/description/base_price[Money]/
  sku), `toProduct`; `productKeys`. (The inventory slice's `ProductBasics` stays; this is the fuller
  editable record.)
- `slugify` in `packages/shared/lib` (or the productListing slice) — a pure, unit-tested helper.
- App server loaders (`import 'server-only'`): `entities/productListing/api/load.server.ts` (list by
  status, rich detail, images, category ids), `entities/category/api/load.server.ts` (list → tree),
  `entities/product/api/load.server.ts` (list, one).
- Features, one per intent: `createListing`, `updateListing`, `listingLifecycle` (publish / unpublish /
  archive — one slice, three hooks, sharing the trivial no-body POST request), `setListingCategories`,
  `uploadListingImage` (the presign→PUT→attach orchestration + XHR progress), `removeListingImage`,
  `manageCategory` (create / update / delete — one slice), `createProduct` (inline). Each: `api/*.request.ts`
  (`orgClient`; `Idempotency-Key` on the money/asset-creating POSTs — create-listing, attach-image,
  create-product, create-category), in-flight `use*` hooks (invalidate the right keys), `router.refresh()`
  on success, error mapped to precise localized copy.
- Widgets: `catalog-list` (status tabs + card feed / table + pagination), `listing-editor` (the
  two-column form + unsaved guard + two-phase create), `listing-image-manager` (uploader + gallery),
  `category-picker` (tree multi-select), `category-manager`, `listing-actions` (the availableActions
  bar + lifecycle sheets).
- Routes: `/catalog`, `/catalog/new`, `/catalog/[id]`, `/catalog/categories`. `nav.ts` **unchanged**
  (the catalog entry + Stock-tab mapping already exist).
- i18n: full **en + ar** catalogs for a new `catalog` namespace (list / editor / images / categories /
  lifecycle / product picker / outcomes + `catalog.actions.*` + `catalog.status.*`); AR parked but paid
  forward (logical CSS so the two-column editor mirrors).
- e2e mock extension + Playwright specs (below). `docs/mobile-shell-pwa.md` §4 amended for the two
  reusable patterns this slice introduces (the desktop-editor responsive layout; the presigned-upload
  atom).

### Out (deferred, with their homes)

- **Public storefront** (`apps/storefront`, `/api/public/*`, the whitelisted `PublicListingDTO`) — a
  separate anonymous app (§7.8), still deferred; this slice is the **admin** authoring surface only.
- **Internal-product delete / full product management** — dangerous (no role gate + raw-500 on
  referenced rows, Gap G3); create-inline only, no delete/list-management screen.
- **Image reorder / alt-text edit** — no backend PATCH (Gap G2); set-once at upload, change = remove +
  re-add.
- **Thumbnails / category count on catalog cards** — not in the lean list row (Gap G1); text-first
  cards until a backend enrichment lands.
- **Org-wide listing search** — no backend param; omitted honestly (no fake search box).

---

## Acceptance criteria

**Catalog list & shell**
1. `/catalog` renders under the **Stock** tab (tab bar highlights Stock on `/catalog` and
   `/catalog/{id}`; More/sidebar entries work). No horizontal body scroll at 375px; safe-area padding
   on docked bars; ≥44px targets.
2. Four URL-driven status tabs (**Draft / Published / Archived / All**) bound to `?status=`, with
   server-truth `total`, envelope pagination, PullToRefresh, shape-matched card skeletons, and a
   designed per-tab empty state. Unknown `?status=` is coerced to All (never a 400 to the user). No
   search box (honest — the backend has no search param).
3. A card shows title, a `StatusBadge` from the machine's intents, `<Money>` sales price, slug
   (`dir="ltr"`), relative updated-at, and a per-card `availableActions()` row (edit + the one legal
   lifecycle verb). No thumbnail or category count (Gap G1).

**Editor**
4. `/catalog/{id}` renders a real two-column layout at `md+` (content left; images/categories/lifecycle
   rail right) collapsing to one column below `md` with the lifecycle docked as a StickyActionBar. A
   listing id not in the org → `notFound()`.
5. Content: title; slug with a slug-from-title helper that stops auto-filling once the slug is
   hand-edited; marketing copy; `<Money>`-edged sales price via `parseEgpInput`. Backend guards
   (`title`/`slug`/`sales_price` required, `sales_price >= 0`) surface as field errors; the slug-clash
   409 and one-listing-per-product 409 map to calm inline messages, not error walls.
6. **Create** (`/new`): pick an existing product **or** create one inline (name/description/base_price/
   sku — no barcode); saving creates the product then the listing. Image + category sections are
   disabled until the listing exists ("save first"), then unlock. The one-listing-per-product 409 is a
   calm state.
7. **Unsaved-changes guard**: navigating away from dirty content warns first.

**Categories**
8. The category picker builds a hierarchy from the flat `GET /categories` (`buildCategoryTree`), lets
   the user tick any set, and saves via the whole-set `PUT`; the unknown-id 400 renders calmly. The
   category **manager** (`/catalog/categories`) creates/edits with a parent picker and deletes
   (MANAGER); the delete-with-children 409 and the cycle/depth/self-parent/slug guards are designed
   inline states.

**Images**
9. The image manager runs presign → **direct PUT to storage with a real progress bar** → attach, with
   `alt_text` + `sort_order` captured at add time; the gallery lists current images from fresh presigned
   GET urls (refetched, never long-cached); remove is a confirmed DELETE. There is **no** reorder or
   alt-edit affordance (Gap G2) — copy states "remove and re-add to change". The attach 400 `"object_key
   does not belong to this listing"` and a failed storage PUT both surface as precise, retryable copy.

**Lifecycle**
10. publish / unpublish / archive are `availableActions()`-driven on both the card and the editor rail
    (no state/role literal in JSX — grep-provable). The only legal verb for the current state shows;
    archive is a one-way-finality confirm; each illegal-transition 409 (`"Only a DRAFT listing can be
    published…"`, `"Only a PUBLISHED listing can be unpublished…"`, `"Listing is already ARCHIVED"`)
    maps to `router.refresh()` + a calm "not available from this state" surface. There is no un-archive
    anywhere.

**Roles & delete**
11. VIEWER read-only; STAFF create/edit/lifecycle/images/categories; MANAGER additionally delete
    (listing + category). Delete-listing (MANAGER) lives behind a full-consequence confirm ("removes the
    listing, its categories, and its images"); internal-product delete is **not** surfaced (Gap G3). No
    role literal in JSX.

**Cross-cutting**
12. All prices via `<Money>` (integer piastres, mapped at the edge; pinned by the number-vs-string
    test); times via `<DateTime>`; every new string via next-intl with **en and ar** entries; logical
    CSS only (the two-column editor mirrors in RTL); motion reduced-motion-gated; FSD boundaries with
    `index.ts` public APIs; **zero new runtime dependencies** (the uploader is a plain XHR PUT).
13. `pnpm -r typecheck`, `pnpm -r test`, `next lint`, production build, and the full Playwright suite
    (mobile **and** desktop) green; existing tests pass unchanged.

---

## Tests

**Unit (co-located, Vitest)**
- `productListingMachine` / `availableActions` truth table: states/terminal/intents; DRAFT→publish,
  PUBLISHED→unpublish, {DRAFT,PUBLISHED}→archive; **no un-archive** from ARCHIVED (empty action set);
  per-role gating (VIEWER none, STAFF all three); `isTerminal(ARCHIVED)`.
- **Price mapper pin**: `toProductListing` maps a JSON **number** `sales_price` (e.g. `200` and
  `49.5`) to the right `Money` piastres; also tolerates a quoted string (resilience) — the guard against
  a silent backend change.
- `slugify`: title → slug (lowercase, spaces→hyphens, strip punctuation, collapse dashes, trim);
  idempotent; the "stop auto-filling once hand-edited" predicate.
- `buildCategoryTree`: assembles roots + nested children from the flat `parent_category_id` list;
  orphan parent → treated as root; stable order.
- `toCategory` / `toProduct` mappers (null-omitted fields → null; base_price → Money).

**Component (co-located, Vitest + Testing Library, jsdom, `renderWithProviders`)**
- Editor validation + the slug helper (auto-fills, then stops after hand-edit); the unsaved-changes
  guard fires on dirty navigation.
- Image uploader states: the presign→PUT→attach **happy path** (progress reaches 100, gallery gains the
  image); a **PUT-to-storage failure** (retryable error, no attach); the **attach 400 `object_key does
  not belong`** calm surface; an **expired-url** gallery image → refresh affordance.
- Lifecycle: a publish 409 (`ApiErrorException`) renders the calm refresh-and-retry surface, the rail
  stays; archive shows the one-way-finality confirm.
- Category manager: the delete-with-children 409 renders as a designed state (not an alert).

**e2e mock (`e2e/mock-api.mjs`) — faithful extension**
- Listing CRUD with exact guards: status-filtered `PageResponse` (lean rows), rich single detail
  (`category_ids` + `images`), create (DRAFT default), the slug 409, the one-listing-per-product 409,
  the lifecycle 409s, MANAGER delete (204, cascades).
- Category CRUD: create/update/delete, the delete-with-children 409, the cycle/depth 400s, slug 409.
- The whole-set categories `PUT` with the unknown-id 400.
- The **three-step image flow**: `presign` returns an `upload_url` pointing at a mock **object-storage
  target** the harness itself serves (`/__storage/{object_key}`, with CORS + an OPTIONS preflight so the
  browser's direct cross-origin PUT resolves) + `object_key` + `expires_in_seconds`; the direct PUT
  stores the bytes; `attach` enforces the `object_key` prefix (400 on mismatch); `GET images` returns
  freshly-"presigned" GET urls (same `/__storage/*`, which serves a 1×1 PNG so `<img>` loads); DELETE.
- `POST /products` (inline create) with the SKU 409.
- `/__reset` fixtures: a **DRAFT** listing, a **PUBLISHED** one, an **ARCHIVED** one, a listing **with
  images**, a **category tree** (roots + children), and a **product with no listing yet** (for create).

**Playwright — mobile (`Pixel 7`) + desktop (this is the desktop-leaning slice), `mobile-catalog.spec.ts`
+ a `desktop-regression` catalog test**
- Catalog status tabs filter the list; empty states per tab.
- Create a listing from an existing product → edit content (slug helper) → assign categories → upload
  an image (presign→PUT→attach, progress, gallery) → **publish** → **unpublish** → **archive**
  (finality confirm) → an illegal-transition calm state → **delete** (MANAGER).
- Category create → delete-with-children 409.
- Desktop: the two-column editor layout + centered dialogs (not bottom sheets).
- No-horizontal-scroll guard across `/catalog`, `/catalog/new`, `/catalog/{id}`, `/catalog/categories`
  on **both** viewports.

---

## New dependencies

**None** (runtime or dev). The uploader is a dependency-free XHR PUT for progress; everything else
composes from the story-02 shell atoms, the shared design system, `Money`/`<DateTime>`, and the existing
test harness.

---

## Backend gaps (companion backlog)

- **G1 — lean list rows:** `GET /product-listings` returns no `images` and no `category_ids`, so catalog
  cards are text-first (no thumbnail, no category count). A `primary_image_url` (and maybe a category
  count) on the list row is the clean fix; the storefront read (§7.8) will want the same.
- **G2 — images are set-once:** no PATCH/PUT on an image — `sort_order` and `alt_text` are fixed at
  attach; the only "edit" is delete + re-add, and reorder is impossible. A `PUT
  .../images/{imageId}` (alt/sort) and a bulk reorder endpoint would unlock a real image manager.
- **G3 — internal-product delete is unguarded:** `ProductHandler.doDelete` performs no
  `requireOrgAccess` (no per-org role gate — every other catalog DELETE is MANAGER) **and** there's no
  referential guard (deleting a product referenced by orders/inventory/a listing raises a raw **500**,
  not a clean 409). Until both are fixed the admin does not surface product delete.
- **G4 — price serialization (resolved/confirmed):** `sales_price`/`base_price` serialize as **JSON
  numbers** (scale-2), not strings (no `WRITE_BIGDECIMAL_AS_PLAIN`, no `ToStringSerializer`). Pinned by a
  mapper test so a future switch to strings can't silently corrupt a price. (This is the brief's open
  question, answered.)

---

## Definition of done

Per `frontend-architecture.md` §8: mirrors the backend FSM + invariants (the `productListingMachine`
verbatim; no un-archive; no action the server would reject for state/role); correct FSD layers with
clean `index.ts` public APIs (boundary lint green); RSC reads + small client islands, shape-matched
skeletons; all prices via `<Money>` (mapped at the edge, pinned) and times via `<DateTime>`; every
string via next-intl **en + ar**; logical CSS (the two-column editor mirrors in RTL); mutations
in-flight (never optimistic) with `Idempotency-Key` on asset-creating POSTs, `router.refresh()` on
success, every error mapped to precise localized copy; role gating via `availableActions()`/`RoleGate`/
`useOrgRole` (no JSX literals); loading/empty/error states all designed (the presigned-upload flow and
every 409 included); motion reduced-motion-gated and keyboard-accessible; unit + component + Playwright
(mobile + desktop) green; all gates green; existing tests unchanged.

---

## As shipped — deviations and discoveries

Shipped as designed, with these deviations and discoveries against the plan:

1. **The stale `productListing` / `category` entity stubs were completed, not rewritten.** The
   committed stubs already carried the verbatim `productListingMachine` (§3.1) and `buildCategoryTree`;
   this slice added the app-side models (`ProductListing` with `Money salesPrice`, `ListingImage`,
   `ProductListingDetail`), the mappers (`toProductListing`/`toProductListingDetail`/`toCategory`), the
   `canDeleteListing`/`canDeleteCategory`/`canEditCategories` role helpers, and set the machine
   `labelKey`s to the `catalog.actions.*` namespace. A new `entities/product` slice (`Product` +
   `toProduct` + `productKeys`) was added and registered in the package exports.
2. **Price serialization confirmed as JSON number (Gap G4).** The DTO types `sales_price`/`base_price`
   as `number | string` (matching the `refund` precedent) and `moneyFromEgp` maps either; a mapper unit
   test pins both. The mock emits real JSON numbers to mirror the backend faithfully.
3. **`slugify` + `deriveSlug` live in `packages/shared/lib/slug.ts`** (reusable, pure, unit-tested) —
   used by both the listing editor and the category manager's name→slug helper.
4. **Lifecycle is one feature slice, three intents.** `features/listingLifecycle` exposes a single
   `useListingLifecycle` hook taking the action (publish/unpublish/archive) — the trivial no-body POSTs
   share one request. The machine's `feature: 'listingLifecycle'` metadata reflects this. (The brief
   listed them separately; consolidating avoided three near-identical slices, same call the inventory
   slice made for its action bar.)
5. **Catalog cards are text-first (Gap G1), and — correcting the brief's seed — show no category
   count.** The lean list row carries neither images nor `category_ids`, so a card shows title, status
   chip, `<Money>` price, slug, and updated-at only. Imagery and categories appear on the editor.
6. **The image uploader uses `XMLHttpRequest`** (`features/uploadListingImage/lib/uploadToStorage.ts`)
   for the direct-to-storage PUT — the one place the app talks to a non-API origin — because XHR
   exposes `upload.onprogress` for a real percentage; `fetch` does not. Zero new dependencies. A failed
   PUT stops before attach; the attach `object_key` 400 surfaces distinctly from a storage failure.
7. **Two-phase create.** `/catalog/new` captures identity + content only; on save it creates the
   product (if inline) then the listing and routes into `/catalog/{id}`, where the image + category
   sections unlock ("save the listing first…"). This matches the backend reality that images/categories
   need a listing id.
8. **Internal-product delete is NOT surfaced** (Gap G3 — the handler has no role gate and raw-500s on a
   referenced product). The create-product path is inline-create only; there is no product management
   or delete screen.
9. **The mobile lifecycle bar docks via `StickyActionBar`** on the editor (create mode docks the
   "Create listing" button; edit mode docks the lifecycle `ListingActions`); the content Save and
   category Save are inline within their sections. The desktop editor is a real
   `md:grid-cols-[1fr_20rem]` two-column layout (content left, rail right), verified by the
   desktop-regression spec.
10. **`nav.ts` unchanged** — the `catalog → stock` entry and Stock-tab mapping have existed since story
    02; the Stock tab lights up on `/catalog/*` and the entry renders in the More sheet + `md+` sidebar.
    No family switcher was added (kept minimal, per the doc's management-family guidance).
11. **The e2e harness stands in for object storage.** The mock's presign returns an `upload_url` on the
    mock's own origin (`/__storage/{object_key}`); the mock serves that path with CORS + an OPTIONS
    preflight (so the browser's direct cross-origin PUT resolves) and a real 1×1 PNG on GET (so gallery
    `<img>`s load). Attach enforces the `{orgId}/listings/{listingId}/` key prefix.
12. **The illegal-transition calm surface is exercised two ways:** an e2e stale-view test (publish a
    DRAFT out-of-band, then click the card's now-stale Publish → the calm "not available from this
    state" line) and a component test (a mocked 409 on `ListingActions`). The editor rail never *offers*
    an illegal verb (the machine only surfaces legal ones), so the 409 is only reachable from a stale
    view — which is exactly what both tests drive.

**Backend gaps still open** (all documented in the Backend-gaps section, none papered over):
- **G1 — lean list rows** (no `images`/`category_ids`): cards are text-first; a `primary_image_url` on
  the list row is the clean fix.
- **G2 — images are set-once** (no PATCH/reorder): alt/order fixed at attach; "change" = remove + re-add.
- **G3 — internal-product delete is unguarded** (no role check + raw 500 on referenced rows): product
  delete is not surfaced.
- **G4 — price serialization** confirmed as JSON number (scale-2), pinned by a mapper test.

---

## Verification ledger (as shipped, 2026-07-05)

- **`pnpm -r typecheck`** — green (packages/shared, packages/entities, apps/admin).
- **`pnpm -r test`** — green: **314 tests** — 30 shared (incl. 5 new `slug`), 134 entities (incl. 12
  productListing, 5 category, 3 product), 150 admin (incl. the 3 `listing-editor/lib` unit tests + new
  component tests: ListingActions 4, ListingImageManager 4, CategoryManager 2, ListingEditor 2).
  Existing tests unchanged and green.
- **`next lint`** — green, no warnings or errors.
- **Production build** — green; all four catalog routes emitted (`/catalog`, `/catalog/new`,
  `/catalog/[id]`, `/catalog/categories`).
- **Playwright** — **57 passed, 0 failed** (`--retries=2`): the 7 new `mobile-catalog` specs (status
  tabs → create-from-product → assign category → presign/PUT/attach image → publish → unpublish →
  archive finality → stale-view calm 409 → MANAGER delete → category create + delete-with-children 409 →
  no-horizontal-scroll on both viewports) and the new `desktop-regression` catalog test (md+ table +
  two-column editor + centered archive dialog). One pre-existing spec (`mobile-fulfillment` golden path,
  story 05) flaked once and passed on retry — the documented single-worker harness flakiness, unrelated
  to this slice.
- **Mock parity** — `e2e/mock-api.mjs` extended with listing CRUD (status-filtered lean `PageResponse`,
  rich detail, the slug 409, one-listing-per-product 409, lifecycle 409s, MANAGER delete), category CRUD
  (delete-with-children 409, slug 409, self-parent/parent-not-found 400s), the whole-set categories PUT
  (unknown-id 400), products list + inline create (SKU 409), and the three-step image flow with a mock
  object-storage target (CORS + OPTIONS preflight + a 1×1-PNG GET) and the `object_key` prefix guard.
  `/__reset` fixtures: a DRAFT listing (Rice), a PUBLISHED one with two images assigned to Food (Beans),
  an ARCHIVED one (Oil), a Drinks › Tea / Food category tree, and Sugar/Salt/Flour/Tea as products with
  no listing yet. Smoke-tested against the running mock before the Playwright run.

No commits, pushes, or PRs — left for the user.

---

## Follow-up — consuming the closed backend gaps (backend `47_feat/catalog-listing-gaps`, commit c0c6260)

After this slice shipped, the backend closed all four flagged gaps (verified against the code, not the
summary). Because story 08 was still an open, unmerged branch, the frontend consumption was folded into
the **same catalog slice** (same branch) rather than fabricating a separate PR number. Each workaround
this slice was shaped around was replaced by the real capability:

### G1 — enriched list rows → thumbnails + category count
`GET /product-listings` now returns each row via `fromView` (category_ids + images, batch-loaded, no
N+1) — the list shape equals the detail shape. Frontend:
- `entities/productListing`: new **`ProductListingRow`** model + `toProductListingRow` (derives
  `thumbnailUrl` from the first image by sort order, plus `imageCount` / `categoryCount`), unit-tested.
- `loadListings` maps to `ProductListingRow[]`; `catalog-list` cards + the `md+` table gain a
  **thumbnail** (neutral placeholder when imageless) and a **category-count** chip (ICU-pluralised).
- The story's "text-first, no thumbnail/count" position (§Backend gaps G1) is **superseded** — cards now
  carry both. The mock's list endpoint emits the enriched shape (`listingRich`).

### G2 — image PATCH → in-place alt-edit + reorder
`PATCH /product-listings/{id}/images/{imageId}` `{alt_text, sort_order}` (STAFF; 400 `"sort_order is
required"` / `"sort_order must be >= 0"`). Frontend:
- New `features/updateListingImage` (added a `patch` verb to the shared `orgClient`).
- `listing-image-manager` rebuilt from a static grid to an editable **row list**: each image can be
  **renamed in place** and **moved up/down** (a neighbour sort-order swap = two PATCHes). The "set-once —
  remove and re-add to change" copy is **removed** (superseding §3 Gap G2). Mock gains the PATCH route.

### G3 — product-delete gate + FK guard → a Products manager
`DELETE /products/{id}` is now MANAGER-gated and translates the Postgres FK violation to a clean 409
(`"Product is referenced by other records (inventory, listings, or orders) and cannot be deleted"`).
The guard keys on the SQLState integrity-constraint class (`23`), so it covers **every** table that
FK-references `product` — `inventory`, `product_listing`, **and `sales_order_line`** — not an
enumerated subset that could drift as new references are added. `ProductDeleteIT` proves each path
returns 409 with the product rolled back (backend commit was amended from `ee4cee5` → `c0c6260` to add
the `sales_order_line`-reference case after verifying that FK is real, not merely logical).
Frontend:
- New `features/deleteProduct` + `canDeleteProduct` (MANAGER) + a lightweight **Products manager** at
  `/catalog/products` (linked from the catalog header) — lists the internal products and lets a MANAGER
  remove an **unreferenced** one; the FK 409 renders as a calm "in use — can't delete" state, never an
  error wall. Create stays inline in the editor; this surface is delete-only. This supersedes §Out
  ("internal-product delete is not surfaced") and Gap G3.

### G4 — price serialization
No change — already JSON number, already pinned. Confirmed unchanged in the gap commit.

**All four gaps are now closed and consumed.** Verification after the follow-up (same gates):
- `pnpm -r typecheck` green; `next lint` green; production build green (the new `/catalog/products` route
  emitted, five catalog routes total).
- `pnpm -r test` — **321 tests** (30 shared, 136 entities incl. `toProductListingRow`, 155 admin incl.
  the reordered image-manager tests + a new `ProductManager` suite).
- **Playwright — 61 passed, 0 failed** (`--retries=2`): the 10 `mobile-catalog` specs now cover the G1
  thumbnail + category count, the G2 in-place alt edit, and the G3 product delete + calm FK-409; the
  `desktop-regression` catalog test asserts the enriched table (Categories column). Mock parity extended
  for the enriched list, the image PATCH (+ sort_order guards), and the MANAGER-gated product delete with
  the FK 409.

Still no commits, pushes, or PRs — left for the user. (If you'd rather ship the gap-consumption as its
own PR stacked on the story-08 branch, the changes are cleanly separable — say the word and I'll split
them.)
