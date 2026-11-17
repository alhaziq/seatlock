# Seatlock

Seatlock is a ticket-booking backend built to stay correct under load. The goal is that 10,000 buyers can compete for 100 seats and exactly 100 of them win: zero oversold, zero double charges. It covers the booking core (events, seats, time-limited holds and hold expiry), payments (idempotent pay requests, an outbox, Kafka, a settlement worker and a ledger), webhooks (signed, retried notifications of booking outcomes), and the proof layer (JWT login, Redis rate limits and a seat cache, Prometheus metrics, a Docker deploy and a load test), on Spring Boot, Postgres, Kafka and Redis.

## Result

**10,000 buyers, 100 seats: 100 holds created, 9,900 refused with a conflict, 100 payments succeeded, 0 oversold, 0 double charges.** Full numbers are in [`load/results/2026-11-local.md`](load/results/2026-11-local.md); the script is [`load/hold-rush.js`](load/hold-rush.js).

| metric | value |
|---|---|
| requests/s | 396.5 (10,204 requests in 25.7 s) |
| hold p95 / p99 | 2.28 s / 2.83 s (median 1.10 s) |
| pay p95 / p99 | 2.49 s / 2.64 s |
| pay replay p95 / p99 | 2.51 s / 2.95 s |
| failed requests | 0 of 10,204 |

These were measured locally on the author's Mac (Apple M1, 8 GiB) against `compose.prod.yaml` on a Colima VM with 4 vCPU and 4 GiB, with k6 on the same host. They were not measured on the public VM, and throughput varied between identical runs on this laptop. The deploy step will replace them.

Correctness is not read from k6 counters. After the run, [`load/verify.sql`](load/verify.sql) checks the database: booked seats equal the seats of the confirmed holds and are at most 100, every confirmed hold owns at least one booked seat, each confirmed hold has one succeeded payment, every payment's ledger entries balance, and no payment is still `PENDING`. All checks passed. Two of the checks (no seat in two confirmed holds, no duplicate `(user, idempotency key)`) cannot fail on real data, because a schema constraint guarantees them; they are tripwires, not evidence. The check is also tested against bad data: three deliberate corruptions each made the matching check fail, as listed in the results file.

The first run, on a 2 vCPU / 2 GiB VM, failed its thresholds: 17 of 10,000 holds returned a status that was neither 201 nor 409, and the status was not recorded. The run was repeated on a 4 vCPU / 4 GiB VM. The [attempt log](load/results/2026-11-local.md#attempt-log-every-run-against-this-stack) lists every run.

## Status: milestone 4 of 4, public deploy pending

1. **Booking core.** Events, seats, holds, expiry. Built.
2. **Payments.** Idempotency keys, outbox, Kafka, a worker, a ledger. Built, against a fake payment provider.
3. **Webhooks.** Signed delivery with retries, a delivery log and dead letters. Built.
4. **Proof.** JWT login, rate limits and seat cache, metrics, Docker deploy files, load test. Built. The public deploy has not been done yet, so there is no live URL and no dashboard screenshot.

## Run it

You need Java 21 and Docker with the Compose plugin. This setup is machine-local: Postgres, Kafka and Redis run in containers and the app runs on your machine. They must be up (`docker compose up -d`) before you start the app. The app has no default login key: set `SEATLOCK_AUTH_JWT_SECRET` to at least 32 bytes or it refuses to start.

```
docker compose up -d
cd app
export SEATLOCK_AUTH_JWT_SECRET=$(openssl rand -hex 32)
./mvnw spring-boot:run
```

With no profile, one process does everything: the web API, the outbox relay, settlement and hold expiry. To run it as two processes, start each in its own terminal (both need the exported secret):

```
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=api
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=worker
```

The `api` profile serves HTTP and runs the outbox relay. It does not expire holds or settle payments. The `worker` profile settles payments and expires holds. It has no web server and no relay. The worker needs no port, so both can run on one machine.

Open Swagger UI at http://localhost:8080/swagger-ui.html. The OpenAPI JSON is at `/v3/api-docs`.

To try it from the command line, sign up two users, log in as each, then create an event, read its seats, hold one, pay for the hold, and poll the payment. Every endpoint except signup, login and the `GET /events` reads needs an `Authorization: Bearer` header. An event has a price in cents and a currency.

```
# the organizer
curl -s -X POST localhost:8080/auth/signup -H 'Content-Type: application/json' \
  -d '{"username":"organizer","password":"organizer-pass"}'
curl -s -X POST localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"organizer","password":"organizer-pass"}'
# copy "accessToken" from the response
ORG_TOKEN=<accessToken>
# the buyer
curl -s -X POST localhost:8080/auth/signup -H 'Content-Type: application/json' \
  -d '{"username":"buyer","password":"buyer-pass1"}'
curl -s -X POST localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"buyer","password":"buyer-pass1"}'
TOKEN=<accessToken>

curl -s -X POST localhost:8080/events -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ORG_TOKEN" \
  -d '{"name":"Launch Night","startsAt":"2027-01-01T20:00:00Z","seatCount":10,"priceCents":2500,"currency":"MYR"}'
# copy "id" from the response
curl -s localhost:8080/events/<event-id>/seats
# copy the "id" of seat "1"
curl -s -X POST localhost:8080/events/<event-id>/holds \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"seatIds":["<seat-id>"]}'
# copy "id" from the response
curl -s -X POST localhost:8080/holds/<hold-id>/pay \
  -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: order-1'
# 202 with a PENDING payment; copy "id" from the response
curl -s localhost:8080/payments/<payment-id> -H "Authorization: Bearer $TOKEN"
# repeat until "status" is SUCCEEDED
curl -s localhost:8080/holds/<hold-id> -H "Authorization: Bearer $TOKEN"
# "status" is now CONFIRMED
```

To see a webhook arrive, run a small receiver on your machine that prints the headers and body of each POST to `/hook`. Webhook URLs must be https and public by default, so for a local receiver start the app with two extra variables:

```
SEATLOCK_WEBHOOKS_ALLOW_HTTP=true SEATLOCK_WEBHOOKS_ALLOW_PRIVATE_ADDRESSES=true ./mvnw spring-boot:run
```

The event's organizer is the user who created it (`organizer` above). Register the receiver as that user before paying, and save the `secret` from the response; it is shown only here, and every `PUT` replaces it:

```
curl -s -X PUT localhost:8080/webhook-endpoint \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $ORG_TOKEN" \
  -d '{"url":"http://localhost:9000/hook"}'
```

Then pay for a hold on that event as above. The receiver gets a signed `booking.confirmed` within a few seconds.

Tokens last one hour; log in again after that. Holds and payments are visible only to the user who made them. Another user gets a 404.

A hold lasts five minutes. Holding the same seat again returns a conflict. Paying again with the same `Idempotency-Key` on the same hold returns the same payment. Use a new key for each new hold: a key is tied to the user and the hold it was first used on.

The payment provider is a fake. Its result depends on the amount in cents:

| Amount ends in | Result |
|----------------|--------|
| `01` | Declined with `INSUFFICIENT_FUNDS`. The payment is `FAILED` and the seats are freed. |
| `02` | Times out. Settlement retries a few times, then fails the payment with `PROVIDER_UNAVAILABLE` and frees the seats. |
| anything else | Approved. The payment is `SUCCEEDED` and the hold is `CONFIRMED`. |

## Webhooks

An organizer registers one URL with `PUT /webhook-endpoint`. `GET /webhook-endpoint` shows it without the secret. Seatlock then POSTs a JSON body to that URL when one of the organizer's holds is paid or expires. [`WebhookEndpointApiTest`](app/src/test/java/dev/seatlock/booking/webhook/WebhookEndpointApiTest.java) covers registration and secret rotation, and [`WebhookFlowTest`](app/src/test/java/dev/seatlock/booking/webhook/WebhookFlowTest.java) runs both events end to end over HTTP.

**Events.** The body is `{"id","type","createdAt","data"}`, where `id` is the delivery id.

- `booking.confirmed`: `data` has `holdId`, `eventId`, `userId`, `seatIds`, `paymentId`, `amountCents` and `currency`.
- `booking.expired`: `data` has `holdId`, `eventId`, `userId` and `seatIds`.

A delivery is queued in Postgres in the same transaction that confirms the booking or expires the hold, so an outcome and its notification both happen or neither does, and a hold is never announced twice. It does not go through Kafka. [`WebhookEnqueueTest`](app/src/test/java/dev/seatlock/booking/webhook/WebhookEnqueueTest.java) covers one delivery per confirmed payment and per expired hold, none for a declined payment, and the rollback case.

**Headers.** `Content-Type: application/json`, `Seatlock-Delivery-Id`, `Seatlock-Event-Type` and `Seatlock-Signature: t=<unix seconds>,v1=<hex>`. The `v1` value is the hex HMAC-SHA256 of `t + "." + body`, keyed with the UTF-8 bytes of the whole secret, `whsec_` prefix included. Each attempt is signed afresh. [`WebhookSignerTest`](app/src/test/java/dev/seatlock/booking/webhook/WebhookSignerTest.java) compares the signer with an independent HMAC, and [`WebhookDispatcherTest.deliversSignedRequest`](app/src/test/java/dev/seatlock/booking/webhook/WebhookDispatcherTest.java) checks the headers on a real request.

To verify a delivery, recompute the HMAC over the raw body (not re-serialised JSON), compare in constant time, and reject an old `t`:

```java
static boolean verify(String secret, String signatureHeader, String rawBody) throws Exception {
    long t = Long.parseLong(signatureHeader.split(",")[0].substring(2));
    String v1 = signatureHeader.split(",")[1].substring(3);
    if (Math.abs(Instant.now().getEpochSecond() - t) > 300) return false; // older than 5 minutes
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] expected = mac.doFinal((t + "." + rawBody).getBytes(StandardCharsets.UTF_8));
    return MessageDigest.isEqual(expected, HexFormat.of().parseHex(v1));
}
```

**Retries.** A 2xx response is a success. Anything else (another status, or a timeout after 2 s to connect or 5 s to read) is a failure, and redirects are not followed. A failed delivery is retried after 10 s, 20 s, 40 s, 80 s and 160 s. After six failed attempts it is `DEAD`. Each attempt is logged with the status and the first 1,000 characters of the response. [`WebhookDispatcherTest`](app/src/test/java/dev/seatlock/booking/webhook/WebhookDispatcherTest.java) covers this in `failsFiveTimesThenSucceeds`, `failingReceiverIsDeadLettered`, `redirectIsAFailure`, `slowReceiverTimesOut` and `responseBodyIsTruncated`.

**At-least-once, unordered.** The dispatcher claims due rows with `for update skip locked` and a three-minute lease, and sends outside any transaction. If the app crashes after the receiver answered and before the attempt is recorded, the delivery goes out again. Receivers must dedupe on `Seatlock-Delivery-Id`. Deliveries are not ordered: a `booking.expired` can arrive before an older `booking.confirmed` is retried. Several dispatchers can run at once: `concurrentDispatchersDeliverEachOnce` shows each delivery is sent once, `expiredLeaseIsReclaimed` shows a crashed claim is picked up later, and `sendIsSkippedOnceItsLeaseHasPassed` shows a slow sender does not send what another replica now owns (all in `WebhookDispatcherTest`).

**Dead letters.** `GET /webhook-deliveries?status=PENDING|DELIVERED|DEAD` lists the caller's newest 50 deliveries, and `GET /webhook-deliveries/{id}` adds the payload and attempt log. `POST /webhook-deliveries/{id}/retry` puts a `DEAD` delivery back in the queue; any other status is a 409, and another organizer's delivery is a 404. [`WebhookDeliveryApiTest`](app/src/test/java/dev/seatlock/booking/webhook/WebhookDeliveryApiTest.java) covers the list, the filter, the detail, the 404, the 409 and the retry.

**URL policy.** By default a URL must be https and must resolve only to public addresses. Loopback, any-local, link-local (including `169.254.169.254`), site-local, multicast and IPv6 unique-local addresses are refused. The check runs when the endpoint is registered and again before every send. [`WebhookUrlPolicyTest`](app/src/test/java/dev/seatlock/booking/webhook/WebhookUrlPolicyTest.java) covers the rules, [`WebhookEndpointApiTest`](app/src/test/java/dev/seatlock/booking/webhook/WebhookEndpointApiTest.java) the check at registration, and `blockedAddressIsNotContacted` in `WebhookDispatcherTest` the check before sending. `SEATLOCK_WEBHOOKS_ALLOW_HTTP` and `SEATLOCK_WEBHOOKS_ALLOW_PRIVATE_ADDRESSES` switch the two rules off for local runs.

## Login

`POST /auth/signup` takes a username (3 to 32 characters of `a-z`, `0-9`, `_`) and a password (8 to 72 bytes) and stores a BCrypt hash. `POST /auth/login` returns an HS256 JWT in `accessToken` that lasts one hour (`seatlock.auth.token-ttl`). The token's `sub` is the user id, and every controller reads the caller from it. [`AuthApiTest`](app/src/test/java/dev/seatlock/booking/auth/AuthApiTest.java) covers signup, duplicate names, validation, and login with a wrong password or an unknown user (both give the same 401). [`SecurityTest`](app/src/test/java/dev/seatlock/booking/auth/SecurityTest.java) covers a missing, garbage, expired, wrongly signed and `alg:none` token (all 401), the public routes, a startup failure when the secret is missing or too short, and that holds and payments are visible only to their owner.

What it is: enough to tie each hold, payment and webhook endpoint to one user. What it is not: there are no refresh tokens, no logout or revocation (a token works until it expires), no roles, no password reset and no email check. Anyone can sign up and create events, and signup is not rate limited. One secret signs and checks tokens, so changing it logs everyone out.

## Rate limits and caching

Redis does two jobs here. It is never the source of truth for who holds a seat.

**Rate limits.** `POST /events/{id}/holds` and `POST /holds/{id}/pay` allow 10 requests per user per 10 seconds. `POST /auth/login` allows 10 per username per 60 seconds, counted even when the password is right. Over the limit the response is a 429 with `Retry-After`. [`RateLimitTest`](app/src/test/java/dev/seatlock/booking/ratelimit/RateLimitTest.java) checks the eleventh hold and the eleventh login, and that a refused hold changes nothing. The counters are fixed windows, so a client can send up to twice the limit across a window edge (see the known limits).

**Fail open.** If Redis is down, the limiter logs a warning and lets the request through, and the seat list is read from Postgres. The Redis timeouts are 500 ms, so a dead Redis costs about a second per request. [`RedisUnavailableTest`](app/src/test/java/dev/seatlock/booking/ratelimit/RedisUnavailableTest.java) runs holds, payments and the seat list against a closed Redis port. The choice is deliberate: a Redis outage should not stop sales, and the limits protect the service, not the data.

**Seat cache.** `GET /events/{id}/seats` is cached in Redis for 1 second (`seatlock.seat-cache.ttl`). A read can therefore show a seat as `AVAILABLE` for up to a second after someone holds it. This cannot oversell, because nothing reads the cache when taking a seat: `POST /events/{id}/holds` locks the rows in Postgres as described below, and a hold on a seat the cache wrongly showed as free fails with a conflict. [`SeatCacheTest`](app/src/test/java/dev/seatlock/booking/event/SeatCacheTest.java) holds a seat behind the cache's back, sees the stale `AVAILABLE`, then sees `HELD` within the TTL.

## Metrics

Each process serves Prometheus metrics on port 8081, along with `/actuator/health`; nothing else is exposed there. The compose network lets Prometheus scrape it, and the port is never published. [`MetricsTest`](app/src/test/java/dev/seatlock/booking/metrics/MetricsTest.java) covers each custom meter and checks the management port exposes only health and Prometheus without a token.

| meter | meaning |
|---|---|
| `seatlock_holds_total{outcome}` | holds created, or refused because a seat was unavailable |
| `seatlock_payments_settled_total{status}` | payments settled, by final status |
| `seatlock_webhook_attempts_total{outcome}` | webhook delivery attempts, by outcome |
| `seatlock_outbox_unpublished` | outbox rows not yet sent to Kafka (read from the database on each scrape) |
| `seatlock_webhook_pending` | webhook deliveries still `PENDING` (read from the database on each scrape) |
| `http_server_requests_seconds` | Spring's request timer, used for hold and pay p95 |

The Grafana dashboard ([`deploy/grafana/dashboards/seatlock.json`](deploy/grafana/dashboards/seatlock.json)) has seven panels: holds per second by outcome, p95 latency of hold and pay, payments settled by status, outbox backlog, webhook attempts by outcome, and pending webhooks. A screenshot is not here yet: the author adds it after the first public deploy.

## Deploy

`compose.prod.yaml` runs Postgres, Kafka, Redis, the api, the worker, Prometheus, Grafana and Caddy on one VM. Only the api is publicly routed (Caddy sends `/` to it, and `/grafana/*` to Grafana). The worker also serves HTTP internally but is not routed, and metrics port 8081 is never published. Steps, VM size and backups are in [`docs/deploy.md`](docs/deploy.md). The public deploy has not been done yet.

## How overselling is prevented

A hold request runs in one database transaction. First it runs `select ... for update` on the requested seats that are still `AVAILABLE`, ordered by id. Postgres locks those rows, so a second request for the same seat waits. When the first transaction commits, the waiting request re-checks the row, sees it is no longer `AVAILABLE`, and drops it from its result. The service then compares the number of locked rows with the number of seats asked for. If they differ, it fails the whole request with a conflict and nothing is written. Otherwise it inserts the hold and marks the seats `HELD` in the same transaction. The code locks rows in id order, so every transaction takes its locks in the same order. Expiry frees a hold and its seats in a single statement.

[`HoldConcurrencyTest`](app/src/test/java/dev/seatlock/booking/hold/HoldConcurrencyTest.java) checks this against a real Postgres. It makes 1,000 concurrent calls to the hold service (not HTTP requests) for 100 seats, on virtual threads, through the default database connection pool. It asserts that exactly 100 succeed and every seat ends `HELD` by its own hold. A second test makes 200 overlapping hold attempts for the same six seats in opposite seat orders. It asserts that exactly one wins, with no deadlock and no server error.

## How double charging is prevented

Paying a hold has several steps, and each one can be repeated: the client can retry, Kafka can deliver a message twice and a worker can crash halfway. Five layers keep the money moving once.

1. **The idempotency key on the pay request.** `POST /holds/{id}/pay` requires an `Idempotency-Key` header. The service first claims the hold, which moves it to `PAYMENT_PENDING`, then looks up the key. A simultaneous request with the same key waits on the hold row, then finds the first request's payment and returns it. The same key on a different hold is a 422. A different key on a hold that is already being paid is a 409. [`PayConcurrencyTest`](app/src/test/java/dev/seatlock/booking/payment/PayConcurrencyTest.java) sends 20 simultaneous calls with one key and asserts one payment and one outbox row. [`PayApiTest`](app/src/test/java/dev/seatlock/booking/payment/PayApiTest.java) covers the replay and the two error cases.
2. **The outbox, written in the same transaction as the payment.** The service inserts the payment and an `outbox` row together, so a payment never exists without its message and a message never exists without its payment. `OutboxRelay` reads unpublished rows with `for update skip locked` and publishes them to the Kafka topic `payments.requested`, keyed by payment id. [`OutboxRelayTest`](app/src/test/java/dev/seatlock/booking/outbox/OutboxRelayTest.java) checks that published rows are not sent again and that four relays running together publish each of 200 rows once.
3. **At-least-once delivery.** The relay can crash after sending and before marking a row published, and Kafka can redeliver. So the consumer must expect each message more than once. It commits an offset only after the payment is settled, skipped or given up on. [`PaymentFlowTest`](app/src/test/java/dev/seatlock/booking/settlement/PaymentFlowTest.java) sends a duplicate message for a settled payment and asserts one charge and two ledger entries.
4. **The row lock and status check in settlement.** `SettlementService.settle` locks the payment row, returns if the status is not `PENDING`, and otherwise charges and records the result in one transaction. A second `settle` waits for the lock, then sees the new status and stops. [`SettlementTest`](app/src/test/java/dev/seatlock/booking/settlement/SettlementTest.java) settles one payment from ten threads at once and asserts one charge and two ledger entries. A success writes two balanced ledger entries and confirms the hold. A decline marks the payment failed and releases the hold.
5. **The payment id as the provider's idempotency key.** The charge can succeed and the app can crash before the transaction commits. On the retry the lock and status check pass, because the payment is still `PENDING`. The provider has already seen this payment id, so it returns the earlier result and moves no money. The fake provider keeps its record of charges in memory, so in this project that protection only lasts while the process runs; a real provider keeps it on its side. `SettlementTest` covers this: a charge made before a crash is not repeated. When settlement gives up after repeated provider timeouts, `failUnsettled` asks the provider whether the charge went through, and completes it if so. `SettlementTest` covers that too.

Retry behaviour is set in `SettlementKafkaConfig` and tested in [`SettlementRetryTest`](app/src/test/java/dev/seatlock/booking/settlement/SettlementRetryTest.java) and `PaymentFlowTest`. Unreadable messages are skipped at once. A provider timeout is retried a bounded number of times, then the payment is failed. Any other failure is retried until it clears, because skipping a real payment would strand its seats. [`ProfileWiringTest`](app/src/test/java/dev/seatlock/booking/ProfileWiringTest.java) checks which components each profile starts.

### Known limits

- The payment provider is a fake. There is no real provider integration.
- There are no refunds.
- The rate limiter uses fixed windows, so a client can send up to twice the limit across a window edge. It is marked with a `shortcut:` comment in `RateLimiter`.
- Login tokens cannot be revoked and there are no refresh tokens, and signup is not rate limited.
- The load numbers come from a laptop running k6 and the whole stack together, not from the public VM.
- Settlement holds the payment row lock during the provider call. This is simple and correct, but a slow provider ties up a database connection.
- The outbox keeps per-key order only within one relay batch. Each key carries one message today, so this does not matter yet.
- An outbox row that can never be sent blocks the rows behind it.
- An unexpected settlement failure blocks its Kafka partition until it clears. There is no dead-letter topic.
- The provider lookup in `failUnsettled` can race a charge that is still in flight on a real provider. The fake provider has no such window.
- Webhook sends within one batch are sequential, so one slow receiver delays the rest of its batch.
- The address check runs before the HTTP client resolves the host again, so DNS rebinding can slip past it.
- Webhook secrets are stored in plain text.
- The dead-letter table is a status (`DEAD`) on `webhook_deliveries`, not a separate table.

Every limit above is marked with a `shortcut:` comment in the code that says when to upgrade it, except the fake provider, the missing refunds, the dead-letter status (a design choice), the token and signup limits and the laptop numbers.

## Design

See [DESIGN.md](DESIGN.md) for the full plan, the non-goals and the remaining milestones.
