-- Cross-org queue indexes for GET /api/admin/queues/{kind} (stories/platform_queues.md, slice 2).
--
-- Why any of this is needed: every V66 index leads with `org_id`
-- (refund_org_status_idx (org_id, status, created_at, id), payment_org_status_idx
-- (org_id, status, received_at, id), and friends). A platform queue asks
-- `WHERE status = … ORDER BY <clock> LIMIT 20` with no org term at all, which matches none of
-- them on its leading column — so without these, each read sorts an entire table to show twenty
-- rows. Slice 1's COUNT(*) tolerated that; a paged, ordered read does not.
--
-- Every index below is partial on its queue's own predicate, so it holds only the backlog and not
-- the table: 5.7k of 34k refunds, 10k of 840k payments, 5k of 1M deliveries. Small, precise, and
-- no meaningful cost on any existing write path.
--
-- MEASURED, not assumed. `EXPLAIN (ANALYZE, BUFFERS)` warm-twice on the seeded `perfdb`
-- (200 orgs / 1M orders / 840k payments, per tools/seed/README.md), page 0 size 20:
--
--   queue                   before      after     index
--   pending-refunds         246.9 ms    0.70 ms   refund_pending_global_idx
--   open-disputes            39.9 ms    0.22 ms   payment_disputed_global_idx
--   failed-emails            40.0 ms    0.25 ms   notification_delivery_failed_global_idx
--   failed-emails ?org_id=  245.6 ms    3.06 ms   notification_org_idx
--   orphan-transactions      12.1 ms   12.2 ms    — none added, see below
--   expired-pending-orders    0.11 ms   0.10 ms   — none needed, V29 already serves it
--
-- TWO QUEUES DELIBERATELY GET NOTHING:
--
--   * `expired-pending-orders` is already served exactly by V29's
--     `idx_so_pending_global (expires_at) WHERE status = 'PENDING_PAYMENT'` — the sweeper's own
--     index, whose predicate and ordering key are this queue's verbatim. It plans as an index
--     scan feeding an incremental sort and finishes in a tenth of a millisecond.
--
--   * `orphan-transactions` measured *no gain*. A candidate
--     `(occurred_at, id) WHERE reconciliation_status = 'ORPHAN'` was built on perfdb and the
--     planner declined to use it: it mis-estimates the NOT EXISTS anti-join (28 rows estimated,
--     4000 actual survive) and so prefers a bitmap scan of the existing
--     `idx_txn_orphan (org_id, recorded_at) WHERE reconciliation_status = 'ORPHAN'` plus a top-N
--     sort. Forcing the ordered scan reaches 1.5 ms, but an index the cost model will not choose
--     is write cost for no read benefit. 12 ms is a fine console read; revisit if the orphan
--     backlog ever grows an order of magnitude. The candidate index was dropped from perfdb.
--
-- Ordering keys match the org-scoped twin's where one exists (refunds `created_at`, payments
-- `received_at`), so the platform queue and its org counterpart list the same rows in the same
-- order — a different clock reads as a bug every time. `id` is the tie-break that makes paging
-- stable across equal timestamps.

-- Queue: pending-refunds. Serves
--   SELECT … FROM refund WHERE status = 'PENDING' ORDER BY created_at, id LIMIT …
CREATE INDEX refund_pending_global_idx
    ON refund (created_at, id)
    WHERE status = 'PENDING';

-- Queue: open-disputes. Serves
--   SELECT … FROM payment WHERE status = 'DISPUTED' ORDER BY received_at, id LIMIT …
CREATE INDEX payment_disputed_global_idx
    ON payment (received_at, id)
    WHERE status = 'DISPUTED';

-- Queue: failed-emails. Serves
--   SELECT … FROM notification_delivery WHERE status = 'FAILED' ORDER BY created_at, id LIMIT …
-- V44's only index here is partial on PENDING (the delivery sweeper's hot path), so FAILED had
-- none at all — this queue was a full scan of every delivery ever attempted.
CREATE INDEX notification_delivery_failed_global_idx
    ON notification_delivery (created_at, id)
    WHERE status = 'FAILED';

-- Queue: failed-emails, narrowed by ?org_id=. `notification_delivery` carries no `org_id` — the
-- org lives on `notification` — so the tenant filter cannot ride the partial index above and the
-- planner had to probe `notification` once per failed delivery (245 ms). With this, it builds the
-- one tenant's notification set from an index-only scan and hash-joins the failed deliveries
-- against it (3 ms). Not partial: the filter is on the org, not on a notification status, and
-- `idx_notification_pending` is partial on PENDING so it cannot serve a DISPATCHED row.
CREATE INDEX notification_org_idx
    ON notification (org_id, id);
