# Fix: the Web Push VAPID env never reached the backend container

> Branch `178_fix/web-push-deploy-env` off `master`, no migration. Follow-up to
> `stories/web_push_channel.md` (V96, PR #177), found while writing the operator's turn-on recipe.

## The defect

`deploy/docker-compose.prod.yml` hands the backend an **explicit** `environment:` map — it does
not pass the server `.env` through wholesale. The three variables V96 reads
(`WEB_PUSH_VAPID_PUBLIC_KEY`, `WEB_PUSH_VAPID_PRIVATE_KEY`, `WEB_PUSH_SUBJECT`) were not in that
map, so an operator who set them in `.env` exactly as the story said would still get
`enabled:false` from `GET /api/me/push/config`, and the admin switch would keep reading
*unavailable* with nothing in any log to say why. (`WHATSAPP_TOKEN_KEY` has the same gap; it is
left alone here — that channel has no operator turning it on yet.)

## The fix

- The compose map gains the three lines, each `${VAR:-}` so an unset `.env` keeps the channel
  honestly off; `WEB_PUSH_SUBJECT` defaults to `mailto:ops@${DOMAIN}`.
- `deploy/env.prod.example` documents them beside the SMTP secrets: how to generate the pair
  (`npx web-push generate-vapid-keys`), the exact encodings, that both-or-neither is enforced at
  boot, and the rotation caveat (rotating orphans every existing browser subscription — staff
  flip the switch off/on per phone).

## Definition of done

- [x] Compose passthrough + example env documented. No code, no tests: the variables' parsing
      and the startup validation are already pinned by `VapidSignerTest`.
- [ ] Operator: generate the pair, add it to the VPS `.env`, `docker compose up -d backend`,
      confirm `GET /api/me/push/config` → `enabled:true`.
