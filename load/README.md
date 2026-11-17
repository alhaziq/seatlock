# Load test: the hold rush

10,000 distinct buyers race for the 100 seats of one event. Each buyer holds one random seat, pays with an `Idempotency-Key`, then replays the same pay request and checks the same payment id comes back. The pass criteria are checked in SQL after the run (`verify.sql`), not by k6 counters.

## Run against the local prod stack

Bring the stack up as in `docs/deploy.md` (`.env` with `SEATLOCK_DOMAIN=localhost`, then `docker compose -f compose.prod.yaml up -d`; wait until every service is healthy). Needs k6 (v1.x or newer) and a Docker VM with at least 4 vCPU / 4 GB; a 2 vCPU / 2 GB VM starves Redis and Postgres and produces failed requests.

```sh
set -a; . ./.env; set +a          # exports SEATLOCK_AUTH_JWT_SECRET
k6 run load/hold-rush.js          # VUS=500 by default: VUS=200 k6 run ... to change
```

`BASE_URL` defaults to `https://localhost`. Caddy serves a locally issued CA certificate there, so the script sets `insecureSkipTLSVerify: true`; remove it when you target a host with a real certificate.

Buyer tokens are minted inside k6 (HS256, key = the secret's UTF-8 bytes) with a random user id per iteration, so no signups are needed and the per-user rate limits (10 per 10 s) never trigger. Only the organizer signs up and logs in, once, in `setup()`. The setup logs `EVENT_ID=<uuid>`.

## Verify

Wait until no payment is PENDING (settlement is asynchronous), then:

```sh
docker compose -f compose.prod.yaml exec -T postgres psql -U seatlock -d seatlock \
  -v event_id=<EVENT_ID> < load/verify.sql
```

Every row must show `passed = t`:

| check | meaning |
|---|---|
| guard | the event exists, has 100 seats and at least one confirmed hold (fails for a wrong event_id) |
| booked seats <= 100 and = seats of confirmed holds | no oversell; booked seats are exactly those of confirmed holds |
| every confirmed hold owns a booked seat | catches a double-sell where two paid holds cover one seat |
| no seat in two confirmed holds | tripwire; guaranteed by the single `seats.hold_id` column |
| one succeeded payment per confirmed hold, none for others | no double charge, no charge without a booking |
| at most one payment per (user, idempotency key) | tripwire; guaranteed by `unique (user_id, idempotency_key)` |
| ledger balanced per payment | debits = credits for every payment; succeeded ones have entries |
| no PENDING payments left | settlement drained (precondition) |

## Reading the k6 summary

- `holds_created` should be 100 (the seats) and `holds_conflicted` about 9,900; `pays_ok` and `pay_replays_matched` equal `holds_created`.
- Any unexpected status is counted in `unexpected_status` (tagged by step and status). `http_req_failed` counts only statuses other than 200, 201, 202 and 409. Both thresholds (`http_req_failed rate==0`, `checks rate==1`) must pass.
- The `p99<60000` thresholds on `name:hold`, `name:pay` and `name:pay_replay` never fail in practice; they exist so k6 prints percentiles for each request type. Read p95/p99 from the `{ name:... }` lines.
- Latency here is dominated by 500 VUs on a laptop sharing CPU with the whole stack; compare runs on the same machine only.
