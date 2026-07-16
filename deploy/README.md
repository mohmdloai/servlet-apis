# Deployment — backend

CI/CD for the inventory backend: **push to `master` → GitHub Actions builds & tests → publishes a
Docker image to GHCR → SSHes into the VPS → pulls & restarts the backend.**

**Your own Caddy is the single edge proxy** (owns 80/443, automatic HTTPS):

```
 push master ─▶ GitHub Actions ─▶ ghcr.io/…/inventory-api:latest ─▶ VPS: docker compose up -d backend
                (build+codegen+test+image)                            │
                                                     your Caddy ──────┼ api.yabta3.com    → backend:8080
                                                     (owns 80/443,     └ files.yabta3.com  → minio:9000
                                                      Let's Encrypt)     db · redis · minio (this stack)
```

## Files

| File | Role |
|------|------|
| `../Dockerfile` | Runtime image (JRE + `EmbeddedTomcatLauncher`). Built from the CI-produced classpath. |
| `../.github/workflows/ci.yml` | PRs: `mvn -Pcodegen verify` against a Postgres service. |
| `../.github/workflows/deploy.yml` | `master`: build+test, push image to GHCR, SSH-deploy. |
| `docker-compose.prod.yml` | This stack: caddy + backend + db + redis + minio. |
| `Caddyfile` | Edge proxy config: api. + files. |
| `env.prod.example` | Template for the server-side `.env` (secrets; never commit it). |
| `backup.sh` | Nightly `pg_dump` + retention, for cron. |

Why the artifact is built in CI (not inside `docker build`): jOOQ generates its Java from a **live,
migrated Postgres**, which CI provides as a service container.

## Cloudflare DNS

On the `yabta3.com` zone, these A records → the VPS IP (`207.180.249.108`), **DNS only (grey
cloud)** so Caddy can complete the Let's Encrypt HTTP-01 challenge (already created ✓):

| Type | Name | Content | Proxy |
|------|------|---------|-------|
| A | `api` | `207.180.249.108` | DNS only |
| A | `files` | `207.180.249.108` | DNS only |

Records must resolve before first boot. (Frontend phase later adds `admin`, `store`, `@`/`www`.)

## One-time VPS setup

```bash
sudo mkdir -p /opt/ststore && sudo chown "$USER" /opt/ststore
cd /opt/ststore
# copy docker-compose.prod.yml, Caddyfile, env.prod.example, backup.sh here
cp env.prod.example .env && nano .env      # fill every CHANGE_ME (DOMAIN=yabta3.com preset)
```

Generate the secrets:
```bash
openssl rand -hex 24     # POSTGRES_PASSWORD
openssl rand -hex 20     # S3_ACCESS_KEY   (and again for S3_SECRET_KEY)
openssl rand -base64 48  # JWT_SECRET      (and again for CUSTOMER_JWT_SECRET)
```

Let the server pull the (private) image from GHCR — create a GitHub PAT with `read:packages`:
```bash
echo "<GITHUB_PAT>" | docker login ghcr.io -u mohmdloai --password-stdin
```
(Or make the GHCR package public and skip this.)

Add the CI deploy key so GitHub Actions can SSH in (public half from the repo-secrets step):
```bash
mkdir -p ~/.ssh && echo "<CI_PUBLIC_KEY>" >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys
```

## GitHub repo secrets (Settings → Secrets → Actions)

| Secret | Value |
|--------|-------|
| `VPS_HOST` | `207.180.249.108` |
| `VPS_USER` | `root` |
| `VPS_SSH_KEY` | private key whose public half is in the VPS `authorized_keys` |
| `VPS_SSH_PORT` | optional, defaults to 22 |

GHCR push uses the built-in `GITHUB_TOKEN` — no secret needed.

## First boot

Caddy binds 80/443 — make sure nothing else on the box is holding those ports first. Then, once the
first image is published (merge to `master`, or run the Deploy workflow manually):

```bash
cd /opt/ststore
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml logs -f backend   # watch Flyway migrate the fresh DB
```

Verify `https://api.yabta3.com` responds (Caddy will have fetched a cert). Then set up backups:
```bash
chmod +x backup.sh
crontab -e     # add:  15 3 * * *  /opt/ststore/backup.sh >> /opt/ststore/backup.log 2>&1
```

**From here on, every merge to `master` auto-deploys the backend.**

## Day-to-day

- **Deploy:** merge to `master`. Manual re-run from the Actions tab (`workflow_dispatch`).
- **Roll back a release:** set `BACKEND_IMAGE=…:<good-sha>` in `.env`, then `docker compose -f docker-compose.prod.yml up -d backend`.
- **Migrations** run automatically on backend startup (Flyway, schema `inventorydb`).

## Local image smoke test (optional)

```bash
mvn -Pcodegen -DskipTests clean install
mvn -pl api dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency
docker build -t inventory-api .
```
