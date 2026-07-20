# 85 — Story: Dozzle log viewer (private, SSH-tunnel only)

> **Status: IMPLEMENTED (deploy config) — not yet applied on the VPS.** Branch
> `85_feat/dozzle-log-viewer`. Adds a lightweight, real-time container-log viewer to the production
> Compose stack, reached **privately over the SSH tunnel we already use** — no public URL, no DNS
> record, no password to manage. Grounded in `docs/dozzle-log-viewer-plan.md` (the plan this story
> executes). Pure ops/deploy change: **no application code, no migration, no API surface** — the only
> touched artifact is `deploy/docker-compose.prod.yml`.
>
> What ships:
> - `dozzle` service in `deploy/docker-compose.prod.yml` — Docker socket mounted **read-only**,
>   filtered to **only this stack's containers**, published **only on the VPS loopback** (`127.0.0.1`).
> - `name: ststore` pinned at the top of the same file so the container-filter label is deterministic.

---

## As a / I want / so that

**As** the operator of the shared VPS,
**I want** to tail and search the live logs of this stack's containers (backend, Postgres, Redis,
MinIO, Caddy, admin, storefront) in a browser,
**so that** I can debug a production incident without hand-rolling `docker logs -f` across seven
containers — and **without** exposing raw logs (they carry JWTs, emails, SQL, stack traces) to the
internet or to the neighbouring app on the same box.

---

## The gap

Today the only way to read prod logs is SSH in and run `docker logs` per container. There is no
multi-container view, no live search, no tail-and-follow across the stack. The plan
(`docs/dozzle-log-viewer-plan.md`) picked **Dozzle** for this: a single ~15–30 MB Go binary, **no
database, no log shipping, no config** — it reads the Docker socket and streams. Negligible footprint
on the shared box, and it fits the existing pattern (one more Compose service).

Explicitly rejected for this box: **Loki + Promtail + Grafana / ELK** — multi-service,
storage-backed, heavy; overkill for one VPS. CLI fallback for ad-hoc use stays `lazydocker` / `ctop`.

---

## Why it is safe on a shared, public box

Dozzle shows **raw** logs of every container it can see — which contain JWTs, customer emails, SQL,
and stack traces. Two structural barriers keep that private, and **both are load-bearing**:

1. **Loopback-only publish.** The service publishes `127.0.0.1:8899:8080`, **not** `0.0.0.0`. It is
   reachable from the VPS itself and nowhere else — **zero public attack surface, nothing to
   password-manage.** Access = the SSH key we already hold. Port `8899` deliberately avoids the
   `8080` the neighbouring proxy on this shared box already binds.
2. **Container filter.** `DOZZLE_FILTER=label=com.docker.compose.project=ststore` scopes the viewer
   to **only this stack's containers**, so the neighbouring app's logs on the shared host are never
   shown. To make that label deterministic (rather than derived from whatever directory Compose is
   launched in), the compose project name is **pinned** with `name: ststore` at the top of the file.
   `/opt/ststore` already yields that name — a no-op today, but it guarantees the filter keeps
   matching if the deploy dir is ever renamed or relocated.

**Read-only socket.** The Docker socket is mounted `:ro` — Dozzle only needs to read.

> **[DECISION — pin the project name]** The plan (§Compose service) sets the filter to the literal
> `ststore` but relied on the launch directory to produce that project name. This story additionally
> pins `name: ststore`, so the filter's correctness no longer depends on the deploy directory's
> basename. Strictly safer, and a no-op for the already-running stack (its project name is already
> `ststore`, so container/network/volume prefixes are unchanged).

---

## The change — `deploy/docker-compose.prod.yml`

Two edits, nothing else in the repo:

```yaml
name: ststore                       # pin the compose project name (deterministic filter label)

# … existing services …

  dozzle:
    image: amir20/dozzle:latest
    restart: unless-stopped
    # Loopback-only publish → reachable from the VPS itself, never from the internet.
    ports:
      - "127.0.0.1:8899:8080"
    environment:
      # Only this stack's containers — not the whole host (keeps the neighbour app private).
      DOZZLE_FILTER: "label=com.docker.compose.project=ststore"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
```

**No** `networks` (it talks to the Docker socket, not the internal container network), **no** Caddy
block, **no** DNS record, **no** env secrets — nothing else changes.

---

## Deploy steps (run on the VPS — out of band, not CI)

1. Pull the updated `deploy/docker-compose.prod.yml` onto the VPS at `/opt/ststore/` (copy from the
   repo — this is the same file the deploy job already refreshes).
2. `docker compose -f docker-compose.prod.yml up -d dozzle`.
3. Confirm it is **loopback-only**: `docker compose -f docker-compose.prod.yml ps` shows
   `127.0.0.1:8899->8080/tcp` (**not** `0.0.0.0:8899->…`). If it shows `0.0.0.0`, stop — the bind is
   the whole protection.

## Access (from your machine)

```bash
ssh -L 8899:localhost:8899 root@207.180.249.108
# then open in your browser:
http://localhost:8899
```

Close the SSH session → the tunnel and all access close with it.

---

## Scope

### In
- `dozzle` service in `deploy/docker-compose.prod.yml` (loopback publish, `:ro` socket, stack filter).
- `name: ststore` pinned at the top of the same file.
- This story doc; the executed plan stays at `docs/dozzle-log-viewer-plan.md`.

### Out (deferred — see the plan's §Later)
- **Any public access to Dozzle.** No `logs.yabta3.com`, no Caddy route, no DNS. If browser-anywhere
  access is ever wanted, the upgrade path is a public host behind Caddy `basic_auth` or Cloudflare
  Access — **auth first, always** — but the SSH tunnel is preferred while it is a solo tool.
- **Uptime / metrics dashboards.** Not log viewers; if wanted later, the light picks are **Uptime
  Kuma** (endpoint uptime + notifications) and **Netdata** (single-agent host/container metrics).
  Dozzle stays the tool for logs.
- **Log persistence / shipping / alerting.** Out of scope by design (that is the rejected
  Loki/ELK direction).

---

## Acceptance (what "done" means)

- [x] `deploy/docker-compose.prod.yml` gains a `dozzle` service: `amir20/dozzle:latest`,
      `restart: unless-stopped`.
- [x] Published on the **loopback only** (`127.0.0.1:8899:8080`), never `0.0.0.0`.
- [x] Docker socket mounted **read-only** (`/var/run/docker.sock:/var/run/docker.sock:ro`).
- [x] `DOZZLE_FILTER` scopes to this stack (`label=com.docker.compose.project=ststore`); project name
      pinned with `name: ststore` so the label is deterministic.
- [x] **No** public exposure: no `networks`, no Caddy block, no DNS record, no env secrets.
- [ ] Applied on the VPS and verified loopback-only via `docker compose … ps` (out-of-band deploy step).

---

## Guard rails (do not regress)

- **Never** change the bind to `0.0.0.0` or add a public route without putting auth in front first —
  the loopback bind is the entire protection.
- **Never** remove `DOZZLE_FILTER` on this shared host — it is what keeps the neighbour app's logs
  out of view.
- Keep the socket mount **`:ro`**.
