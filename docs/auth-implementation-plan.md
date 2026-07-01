# JWT Auth with Redis-Backed Refresh Token Rotation

> **STATUS (post-implementation):** This document was the working spec for the auth branch. After review, the design changed in two important ways. Read these first — the rest of the doc is preserved for history but is partially out of date.
>
> **1. Customer is a CRM record, not an authenticated user.** All references in this doc to "Option A: customers + staff in one `app_user` table", `CUSTOMER` actor type, V10 (`Add_customer_to_actor_type`), V13 credential-copy migration, `SecurityContext.isCustomer()`, `ActorContext.customer(...)`, and the multi-factory `CustomerService` are obsolete. `customer.password_hash` is dropped (V13 just drops the column), `customer` is org-scoped CRM data, and `app_user` only ever holds staff. Future customer self-serve goes via magic links — see `docs/customer-portal-future.md`.
>
> **2. `tenant` → `org` rename.** Everywhere this doc says `tenant`, `TenantRole`, `tenantRoles`, or `tenant_roles` (JWT claim), the implementation uses `org`, `OrgRole`, `orgRoles`, `org_roles`. The `org` table also exists now as the FK target for `user_org_role.org_id` (added in V15). Every business table carries `org_id NOT NULL`, and every service/repository method takes `orgId` as the first parameter — explicit, compiler-enforced.
>
> See `CLAUDE.md` for current API endpoints and architecture.

---

## §Post-plan additions (impersonation + per-device kill-switch)

> Two later slices extend this design on the same spine (JwtAuthFilter → AuthService →
> RefreshTokenStore → SecurityContext) without changing its posture (cookies-only, Redis-authoritative,
> 401-no-fallback, fail-closed). Full designs: `docs/impersonation.md` and `stories/impersonate_user.md`.
> The items below amend specific contracts stated later in this doc.

**1. `token_version` now has a THIRD warm-path — this amends the "login/refresh only" invariant (lines 29, 181, 190).** An impersonation overlay's `sub` is the *target*, so `JwtAuthFilter` validates it against the **target's** cached `token_version`. A target who never logged in has no `user:ver:{target}` key, and "cache miss ⇒ invalid" would 401 every overlay request (an overlay has no refresh token to re-login with). So `AuthService.impersonate()` primes `cacheTokenVersion(targetId, version)` when minting. This *preserves* revocation: the target's `logout-all` re-caches a higher version and kills the overlay. (This gap was caught by end-to-end testing, not the unit layer.)

**2. New Redis key + `logout`/`revokeSession` behavior change (amends lines 163–171, 186, 189).** Per-device access-token kill-switch. New key `rt:revoked-fam:{familyId} → "1"` with **TTL = access-token TTL** (default 900s; self-expiring, so the denylist stays bounded and clears once the token would have expired anyway). Access tokens minted on login/refresh now carry a `fam` claim (= the family id = one device). `JwtAuthFilter` rejects with `401 "Session revoked"` when a token's `fam` is denylisted. `revokeSession()` (`DELETE /api/auth/sessions/{familyId}`) **and** `logout()` now call `denyFamilyAccess(...)` — so a single-device revoke kills that device's outstanding **access** token immediately, not just its ability to refresh. New store methods: `denyFamilyAccess`, `isFamilyAccessRevoked`. `logoutAll` is unchanged (still the per-user `token_version` bump).

**3. New JWT claims (amends `generateAccessToken`, line 99).** `fam` (device binding, above) on login/refresh tokens; `act` / `act_tier` / `act_scope_org` / `act_mode` on impersonation overlays. An overlay carries **no `fam`** (device-less — killed by its ~5-min TTL or the target's `logout-all`, not by a per-device revoke) and **never** carries `system_roles`; org-tier overlays scope `org_roles` to the one authorized org.

**4. New endpoints (extends AuthServlet, lines 235–241) and wiring.** `POST /api/auth/stop-impersonating`; `POST /api/admin/impersonate/{userId}` (platform ADMIN full-write / SUPPORT read-only); `POST /api/orgs/{orgId}/impersonate/{userId}` (real org OWNER only — the system-admin bypass is deliberately not honored here). `AuthService` constructor grows to `(UserRepository, RefreshTokenStore, JwtUtil, ImpersonationEventRepository, impersonationTtlMillis)`. New migration `V41` adds `impersonation_event` (Layer-1 audit) and `inventory_log.impersonator_id` (Layer-2 stamping). Cookie writing is extracted to `AuthCookies` (impersonation writes the access cookie **only** — never a refresh cookie).

---

## Context

The inventory API currently has zero authentication — anyone can send `X-Actor-Id` headers and claim any identity. This plan adds JWT-based authn/authz with Redis-backed refresh token rotation (family-based theft detection), unified identity (Option A: customers + staff in one `app_user` table), and ABAC policies as Java code.

## Architecture Overview

```
Request → CorsFilter → JwtAuthFilter → Servlet → Service (receives ActorContext only)
                            ↓                          ↓
                       validates JWT             writes InventoryLog
                       checks token_version      (unchanged audit flow)
                       builds SecurityContext
```

- **Access token**: JWT, 15 min TTL, HMAC-SHA256, delivered via Bearer header (services) or cookie (browsers). Cookie config: `HttpOnly, Secure, SameSite=Strict, Path=/, Max-Age=900`. Tokens are **never** returned in JSON response bodies.
- **Refresh token**: Opaque UUID, 7 day TTL, stored as SHA-256 hash in Redis, family-based rotation. Cookie config: `HttpOnly, Secure, SameSite=Strict, Path=/api/auth, Max-Age=604800`. Scoped to `/api/auth` path only — prevents the refresh token from being sent on every API request, reducing exposure window.
- **Token version**: `app_user.token_version` in PostgreSQL, warmed into Redis on login/refresh only (never lazily on every request).
- **ActorContext unchanged**: SecurityContext.toActorContext() bridges to existing service layer.

---

## Phase 1: Infrastructure

### MODIFY `docker-compose.yaml`
- Add `redis` service (redis:7-alpine, port 6379, healthcheck)
- Add `redis_data` volume

### MODIFY `pom.xml` (parent)
- Add version properties: `jedis.version=5.1.0`, `jjwt.version=0.12.6`, `jbcrypt.version=0.4`
- Add `<dependencyManagement>` entries: jedis, jjwt-api, jjwt-impl (runtime), jjwt-jackson (runtime), jbcrypt

### MODIFY `common/pom.xml`
- Add: jedis, jbcrypt, jjwt-api, jjwt-impl (runtime), jjwt-jackson (runtime)

### MODIFY `service/pom.xml`
- Add: jedis (for RefreshTokenStore)

### CREATE `common/src/main/resources/redis.properties`
- host, port (6379), maxTotal, maxIdle, timeout

---

## Phase 2: Domain Models

### MODIFY `domain/.../model/ActorType.java`
- Add `CUSTOMER` value

### MODIFY `domain/.../model/ActorContext.java`
- Add `customer(String customerId)` static factory

### CREATE `domain/.../model/SystemRole.java`
- Enum: `ADMIN`, `SUPPORT`

### CREATE `domain/.../model/TenantRole.java`
- Enum: `OWNER`, `MANAGER`, `STAFF`, `VIEWER`

### CREATE `domain/.../model/SecurityContext.java`
- Record: `(UUID actorId, ActorType actorType, Set<SystemRole> systemRoles, Map<UUID, Set<TenantRole>> tenantRoles, Set<String> allowedActions, int tokenVersion)`
- Methods: `toActorContext()`, `hasSystemRole()`, `hasTenantRole()`, `isSystemAdmin()`, `isService()`, `isCustomer()`

### CREATE `domain/.../model/Environment.java`
- Record: `(Instant requestTime, String sourceIp, String userAgent)`
- No `isBusinessHours()` method here — timezone is a tenant config concern, not a domain primitive. ABAC policy callers resolve timezone from tenant config and compare directly.

### CREATE `domain/.../model/AppUser.java`
- Mutable POJO (follows existing Product/Customer pattern): id, email, passwordHash, actorType, active, tokenVersion, createdAt, updatedAt

### CREATE `domain/.../model/UserTenantRole.java`
- Simple model: userId, tenantId, role

### CREATE `domain/.../repository/UserRepository.java`
- Interface: `findById`, `findByEmail`, `insert`, `update`, `incrementTokenVersion`, `getTokenVersion`, `findTenantRoles`

### CREATE `domain/.../repository/UserRepositoryFactory.java`
- Factory interface: `create(Object ctx)` (matches existing pattern)

---

## Phase 3: Common Utilities

### CREATE `common/.../common/RedisFactory.java`
- Mirrors `DataSourceFactory` pattern — loads `redis.properties`, returns `JedisPool`

### CREATE `common/.../common/security/JwtUtil.java`
- Constructor: `(String base64Secret, long accessTtlMillis)`
- **Startup validation**: constructor throws `IllegalArgumentException` if secret is less than 32 bytes after Base64 decode. No silent fallback to random keys.
- `generateAccessToken(userId, actorType, tenantRoles, systemRoles, allowedActions, tokenVersion)` → signed JWT
- `parseAndVerify(token)` → jjwt Claims (throws JwtException)

### CREATE `common/.../common/security/PasswordHasher.java`
- Static: `hash(rawPassword)` → bcrypt, `verify(rawPassword, hash)` → boolean

### CREATE `common/.../exception/AuthenticationException.java`
- Extends AppException(401, message)

### CREATE `common/.../exception/AuthorizationException.java`
- Extends AppException(403, message)

---

## Phase 4: DB Migrations

### CREATE `V10__Add_customer_to_actor_type.sql`
- `ALTER TYPE actor_type ADD VALUE 'CUSTOMER';`

### CREATE `V11__Create_app_user_table.sql`
- `app_user(id UUID PK, email UNIQUE, password_hash, actor_type, active, token_version, created_at, updated_at)`

### CREATE `V12__Create_user_tenant_role_table.sql`
- `CREATE TYPE tenant_role AS ENUM ('OWNER','MANAGER','STAFF','VIEWER')`
- `user_tenant_role(user_id, tenant_id, role)` — composite PK, FK to app_user ON DELETE CASCADE

### CREATE `V13__Migrate_customer_credentials_to_app_user.sql`
- INSERT with idempotency guard (safe if migration is partially applied and retried):
  ```sql
  INSERT INTO app_user (id, email, password_hash, actor_type, active, token_version)
  SELECT id, email, password_hash, 'CUSTOMER', TRUE, 0
  FROM customer
  WHERE NOT EXISTS (
      SELECT 1 FROM app_user WHERE app_user.id = customer.id
  );
  ```
- ALTER TABLE customer DROP COLUMN password_hash
- ALTER TABLE customer ADD FK to app_user(id)

### After Phase 4: run `mvn generate-sources -Pcodegen -pl repository`

---

## Phase 5: Repository Layer

### CREATE `repository/.../UserRepositoryImpl.java`
- jOOQ impl of UserRepository, uses generated APP_USER and USER_TENANT_ROLE tables
- Maps generated records to domain AppUser, handles actor_type enum conversion

### CREATE `repository/.../UserRepositoryFactoryImpl.java`
- Factory: `create(Object ctx)` → `new UserRepositoryImpl((DSLContext) ctx)`

### MODIFY `repository/.../CustomerRepositoryImpl.java`
- Remove all CUSTOMER.PASSWORD_HASH references from insert/update/toCustomer

### MODIFY `domain/.../model/Customer.java`
- Remove passwordHash field, getter, setter

---

## Phase 6: Service Layer

### CREATE `service/.../service/auth/RefreshTokenStore.java`
Redis data model:
```
rt:{sha256(token)}     → JSON {userId, familyId, deviceInfo, sourceIp, issuedAt}  TTL 7d
rt:family:{familyId}   → SET of token hashes                                      TTL 8d
rt:user:{userId}       → SET of family IDs                                         No TTL
user:ver:{userId}      → integer (token_version)                                   No TTL
```
Methods: `store`, `find`, `revoke`, `revokeFamily` (theft detection), `revokeAllForUser`, `listSessions`, `cacheTokenVersion`, `getCachedTokenVersion`

**Cleanup contract**: `revokeFamily()` and `revokeAllForUser()` SREM dead family IDs from `rt:user:{userId}` after revoking. Prevents unbounded memory growth from users who never explicitly log out.

### CREATE `service/.../service/auth/AuthService.java`
Constructor: `(UserRepository userRepo, RefreshTokenStore store, JwtUtil jwt)`
- **Direct repository injection** — follows the same pattern as `ProductService(ProductRepository repo, DSLContext dsl)`. AuthService receives a `UserRepository` instance created at startup (bound to the root `DSLContext`), not a factory. This is correct because no AuthService method needs to coordinate multiple repositories in a single transaction:
  - `login()`: reads user by email + loads tenant roles (read-only, single repo)
  - `refresh()`: all Redis operations, no DB writes
  - `logoutAll()`: `incrementTokenVersion` is a single atomic UPDATE statement (single repo)
  - `isTokenVersionValid()`: Redis-only
- **No DSLContext field** — AuthService never touches jOOQ directly. All DB access flows through the injected `UserRepository`.
- **Token version cache warming**: `login()` and `refresh()` write the current `token_version` into Redis immediately after reading it from DB. `isTokenVersionValid()` reads from Redis only — if cache miss (Redis flush), it returns invalid and forces re-login. No lazy DB fallback on the hot path.
- `login(email, rawPassword, deviceInfo, sourceIp)` → sets cookies via response (no token bodies)
  - Find user, verify password, load tenant roles, issue JWT + refresh token, create new family, **warm token_version into Redis**
- `refresh(rawRefreshToken, sourceIp)` → rotated tokens via cookies
  - Hash token, lookup in Redis. If not found → check if family exists → THEFT DETECTED → revoke family. If found → revoke old, issue new in same family, **re-warm token_version**
- `logout(rawRefreshToken)` → revoke single token
- `logoutAll(userId)` → increment token_version in DB + Redis, revoke all refresh tokens
- `listSessions(userId)` → list active families
- `revokeSession(userId, familyId)` → revoke specific family
- `isTokenVersionValid(userId, claimedVersion)` → Redis-only check, cache miss = invalid (forces re-login)

### MODIFY `service/.../CustomerService.java`
- **Add `UserRepositoryFactory` to constructor** — new signature: `CustomerService(DSLContext rootDsl, CustomerRepositoryFactory custRepoFactory, UserRepositoryFactory userRepoFactory)`. CustomerService needs the factory (not a direct `UserRepository`) because `create()` must coordinate two repositories inside a single transaction — matching the existing multi-factory pattern used by `InventoryService(DSLContext, InventoryRepositoryFactory, InventoryLogRepositoryFactory)`.
- `create(email, rawPassword)` → in single transaction:
  ```java
  rootDsl.transactionResult(cfg -> {
      DSLContext txDsl = DSL.using(cfg);
      CustomerRepository custRepo = custRepoFactory.create(txDsl);
      UserRepository userRepo = userRepoFactory.create(txDsl);

      // 1. Check email uniqueness (against app_user, the source of truth)
      // 2. Hash password via PasswordHasher.hash(rawPassword)
      // 3. Create AppUser (id=generated, email, hash, actorType=CUSTOMER, active=true, tokenVersion=0)
      // 4. Create Customer (same id as AppUser, email)
      // Both share txDsl — atomic commit or full rollback
  });
  ```
- `update(id, email)` → no longer accepts passwordHash (password changes go through a future change-password endpoint on AuthServlet)

---

## Phase 7: API Layer

### CREATE `api/.../filter/CorsFilter.java`
- Jakarta Filter registered **before** JwtAuthFilter in the chain
- Handles preflight `OPTIONS` requests — returns 200 with CORS headers immediately (no auth check)
- Sets `Access-Control-Allow-Origin`, `Access-Control-Allow-Methods`, `Access-Control-Allow-Headers`, `Access-Control-Allow-Credentials: true`
- **Origin whitelist**: read from `CORS_ALLOWED_ORIGINS` env var, comma-separated. Default (when env var is unset): `http://localhost:3000,http://localhost:5173`. The filter splits on `,`, trims each entry, and checks the request's `Origin` header against the set. If origin is not in the set, no `Access-Control-Allow-Origin` header is returned (browser blocks the request). Defaults cover Vite (5173) and CRA/Next.js dev (3000).

### CREATE `api/.../filter/RateLimitFilter.java`
- Jakarta Filter on `/api/auth/*`
- Sliding window counter per source IP, stored in Redis, separate keys and thresholds per action:
  - `rl:login:{ip}` — 10 attempts per minute (brute force protection)
  - `rl:refresh:{ip}` — 30 attempts per minute (higher: a user with multiple tabs fires concurrent refreshes every 15 min)
- After limit: 429 Too Many Requests
- Registered **after** CorsFilter, **before** JwtAuthFilter

### CREATE `api/.../filter/JwtAuthFilter.java`
- Jakarta Filter on `/api/*`, skips `/api/auth/login` and `/api/auth/refresh`
- Extracts JWT from Bearer header (first) then `access_token` cookie
- Parses JWT via JwtUtil, checks token_version via AuthService.isTokenVersionValid (Redis-only)
- Builds SecurityContext + Environment, sets as request attributes
- Returns 401 JSON on failure — **no fallback to old header-based actor extraction**

### CREATE `api/.../servlet/AuthServlet.java`
- `POST /api/auth/login` → authenticate, set cookies (`access_token` at `/`, `refresh_token` at `/api/auth`), return JSON body with `expiresIn` and user metadata only (**no tokens in body**)
- `POST /api/auth/refresh` → rotate tokens via cookies (same path scoping), return JSON with `expiresIn` only
- `POST /api/auth/logout` → revoke refresh token, clear cookies (requires auth)
- `POST /api/auth/logout-all` → increment version, revoke all (requires auth)
- `GET /api/auth/sessions` → list active sessions (requires auth)
- `DELETE /api/auth/sessions/{familyId}` → revoke specific session (requires auth)

### CREATE DTOs
- `LoginRequest` (email, password)
- `AuthResponse` (expiresIn, userId, actorType) — **no tokens in body**, cookies are the sole transport
- `SessionResponse` (familyId, deviceInfo, sourceIp, lastIssuedAt)

### MODIFY `api/.../dto/ApiError.java`
- Add `case 401 -> "Unauthorized"`, `case 403 -> "Forbidden"`, `case 429 -> "Too Many Requests"` to httpPhrase

### MODIFY `api/.../dto/CreateCustomerRequest.java`
- Rename `passwordHash` field to `password`

### MODIFY `api/.../dto/UpdateCustomerRequest.java`
- Remove `passwordHash` field

### MODIFY `api/.../config/AppConfig.java`
- Add fields: `JedisPool jedisPool`, `JwtUtil jwtUtil`, `UserRepository userRepository`, `UserRepositoryFactory userRepositoryFactory`, `RefreshTokenStore refreshTokenStore`, `AuthService authService`
- **JWT_SECRET from env var** — `AppConfig` constructor throws `RuntimeException("JWT_SECRET env var is required and must be at least 32 bytes")` if missing or too short. No silent fallback. For dev convenience, document the setup in README/CLAUDE.md.
- **Wiring** — follows the two established patterns:
  ```java
  // Infrastructure
  this.jedisPool = RedisFactory.build();
  this.jwtUtil = new JwtUtil(System.getenv("JWT_SECRET"), 900_000L);

  // Repositories — direct instance for AuthService (ProductService pattern)
  this.userRepository = new UserRepositoryImpl(dsl);
  // Factory for CustomerService (InventoryService multi-factory pattern)
  this.userRepositoryFactory = new UserRepositoryFactoryImpl();

  // Services
  this.refreshTokenStore = new RefreshTokenStore(jedisPool);
  this.authService = new AuthService(userRepository, refreshTokenStore, jwtUtil);
  this.customerService = new CustomerService(dsl, customerRepositoryFactory, userRepositoryFactory);
  ```
- Shutdown: close jedisPool + dataSource

### MODIFY `api/.../EmbeddedTomcatLauncher.java`
- Register filters in order: CorsFilter → RateLimitFilter → JwtAuthFilter (via `FilterDef` + `FilterMap`)
- Register AuthServlet at `/api/auth/*`

### MODIFY `api/.../servlet/InventoryServlet.java`
- `extractActor()` reads SecurityContext from request attribute — **no fallback to X-Actor-Id/X-Actor-Type headers**. If SecurityContext is missing, the request already failed at the filter. Remove the old header-parsing code entirely.

### MODIFY `api/.../servlet/CustomerServlet.java`
- Same: extract actor from SecurityContext only, no header fallback
- Update create call: pass raw password instead of pre-hashed

---

## Filter Chain Order

```
CorsFilter (/api/*)         → handles OPTIONS preflight, sets CORS headers
  ↓
RateLimitFilter (/api/auth/*) → sliding window per IP, 429 on excess
  ↓
JwtAuthFilter (/api/*)      → skips /api/auth/login and /api/auth/refresh
  ↓
Servlets
```

---

## File Count Summary

- **30 files created** (domain models, repos, services, 3 filters, servlet, DTOs, migrations, properties)
- **16 files modified** (pom files, docker-compose, existing servlets/repos/services, DTOs)

---

## Verification

1. `docker-compose up -d` — verify Redis + PostgreSQL start
2. `mvn generate-sources -Pcodegen -pl repository` — verify codegen includes new tables
3. `mvn clean install` — verify all modules compile
4. Set `JWT_SECRET` env var (base64, ≥32 bytes), run `mvn exec:java -pl api` — verify server starts, verify startup fails without JWT_SECRET
5. Manual test flow:
   - `POST /api/auth/login` → cookies set, body has `expiresIn` only (no tokens)
   - Use cookie to `GET /api/products` → 200
   - Request without cookie or Bearer → 401
   - `POST /api/auth/refresh` → new cookies, old refresh token invalidated
   - `POST /api/auth/logout` → cookies cleared, refresh token revoked
   - `POST /api/auth/logout-all` → all sessions invalidated, token_version incremented
   - Reuse old refresh token → 401 + theft detection logs + family revoked
6. Rate limiting: fire 11 rapid `POST /api/auth/login` from same IP → 10th succeeds, 11th returns 429
7. CORS: send `OPTIONS /api/products` → 200 with CORS headers, no 401
8. Verify old `X-Actor-Id` header is completely ignored — sending it has no effect
9. Verify `InventoryLog` still records correct `actor_id`/`actor_type` via SecurityContext.toActorContext()
