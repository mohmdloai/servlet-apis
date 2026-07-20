# Dozzle log viewer — plan

Add a lightweight, real-time container log viewer to the deploy stack, reached **privately over an
SSH tunnel** (no public URL, no password). **Plan only — not yet applied.**

## Why Dozzle

- Purpose-built web UI for live Docker container logs (tail, search, multi-container split view).
- Tiny: one ~15–30 MB Go binary, **no database, no log shipping, no config**. Reads the Docker
  socket and streams. Negligible footprint on the shared VPS.
- Fits the existing pattern: one more Compose service.
- Shared-box safe: filtered to **only this stack's containers**, so the neighbouring Coolify app's
  logs are never shown.

Explicitly rejected for this box: Loki+Promtail+Grafana / ELK (multi-service, storage-backed,
heavy — overkill for one VPS). CLI fallback for ad-hoc use: `lazydocker` or `ctop`.

## Protection: not exposed publicly

Dozzle shows raw logs of every container (backend, Postgres, Redis, MinIO) — which contain JWTs,
emails, SQL, and stack traces. So it is **not** given a public domain, Caddy route, or DNS record.
Instead it binds to **localhost on the VPS only** and is reached through the SSH connection we
already use. Protection = the SSH key; zero public attack surface, nothing to password-manage.

## Changes to make

### Compose service — `deploy/docker-compose.prod.yml`
Add a `dozzle` service: Docker socket mounted **read-only**, filtered to the `ststore` stack, and
published **only on the VPS's loopback** (not `0.0.0.0`). Host port `8899` avoids the `8080` the
neighbouring Coolify proxy already binds.

```yaml
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
No `networks`, no Caddy block, no DNS record, no env secrets — nothing else changes.

## Deploy steps
1. Add the `dozzle` service above to `/opt/ststore/docker-compose.prod.yml` (copy from repo).
2. `docker compose -f docker-compose.prod.yml up -d dozzle`.
3. Confirm it's loopback-only: `docker compose ... ps` shows `127.0.0.1:8899->8080/tcp` (not `0.0.0.0`).

## Access (from your machine)
```bash
ssh -L 8899:localhost:8899 root@207.180.249.108
# then open in your browser:
http://localhost:8899
```
Close the SSH session → the tunnel and all access close with it.

## Security notes
- **Read-only** socket mount (`:ro`) — Dozzle only needs to read.
- **Loopback bind** (`127.0.0.1:…`) is the whole protection — never change it to `0.0.0.0` or add a
  public route without putting auth in front first.
- The `DOZZLE_FILTER` scope keeps the neighbour app's logs out of view; do not remove it on this
  shared host.

## Later (optional, out of scope here)
If uptime alerts or metrics dashboards become worth it, the light picks are **Uptime Kuma**
(endpoint uptime + notifications) and **Netdata** (single-agent host/container metrics). Neither is
a log viewer — Dozzle stays the tool for logs. If you ever do want browser-anywhere access to
Dozzle, the upgrade path is a public `logs.yabta3.com` behind Caddy `basic_auth` or Cloudflare
Access — but the SSH tunnel is preferred while it's a solo tool.
