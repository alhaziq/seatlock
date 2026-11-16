# Seatlock

Seatlock is a ticket-booking backend built to stay correct under load. The goal is that 10,000 buyers can compete for 100 seats and exactly 100 of them win: zero oversold, zero double charges. Today the project covers the booking core (events, seats, time-limited holds and hold expiry) payments (idempotent pay requests, an outbox, Kafka, a settlement worker and a ledger) and webhooks (signed, retried notifications of booking outcomes), on Spring Boot, Postgres and Kafka.

## Status: milestone 3 of 4

1. **Booking core.** Events, seats, holds, expiry. Built.
2. **Payments.** Idempotency keys, outbox, Kafka, a worker, a ledger. Built, against a fake payment provider.
3. **Webhooks.** Signed delivery with retries, a delivery log and dead letters. Built.
4. **Proof.** Load test, metrics, public deploy, measured results. Not built yet.

No performance numbers are published. They will arrive with the milestone 4 load test.

## Run it

You need Java 21 and Docker with the Compose plugin. This setup is machine-local: Postgres and Kafka run in containers and the app runs on your machine. Postgres and Kafka must be up (`docker compose up -d`) before you start the app.

```
docker compose up -d
cd app
./mvnw spring-boot:run
```

With no profile, one process does everything: the web API, the outbox relay, settlement and hold expiry. To run it as two processes, start each in its own terminal:

```
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=api
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=worker
```

The `api` profile serves HTTP and runs the outbox relay. It does not expire holds or settle payments. The `worker` profile settles payments and expires holds. It has no web server and no relay. The worker needs no port, so both can run on one machine.

Open Swagger UI at http://localhost:8080/swagger-ui.html. The OpenAPI JSON is at `/v3/api-docs`.

To try it from the command line, create an event, read its seats, hold one, pay for the hold, then poll the payment. Callers identify themselves with an `X-User-Id` header holding a UUID. An event has a price in cents and a currency.

```
curl -s -X POST localhost:8080/events -H 'Content-Type: application/json' \
  -H 'X-User-Id: 22222222-2222-2222-2222-222222222222' \
  -d '{"name":"Launch Night","startsAt":"2027-01-01T20:00:00Z","seatCount":10,"priceCents":2500,"currency":"MYR"}'
# copy "id" from the response
curl -s localhost:8080/events/<event-id>/seats
# copy the "id" of seat "1"
curl -s -X POST localhost:8080/events/<event-id>/holds \
  -H 'Content-Type: application/json' -H 'X-User-Id: 11111111-1111-1111-1111-111111111111' \
  -d '{"seatIds":["<seat-id>"]}'
# copy "id" from the response
curl -s -X POST localhost:8080/holds/<hold-id>/pay \
  -H 'X-User-Id: 11111111-1111-1111-1111-111111111111' -H 'Idempotency-Key: order-1'
# 202 with a PENDING payment; copy "id" from the response
curl -s localhost:8080/payments/<payment-id>
# repeat until "status" is SUCCEEDED
curl -s localhost:8080/holds/<hold-id>
# "status" is now CONFIRMED
```

To see a webhook arrive, run a small receiver on your machine that prints the headers and body of each POST to `/hook`. Webhook URLs must be https and public by default, so for a local receiver start the app with two extra variables:

```
SEATLOCK_WEBHOOKS_ALLOW_HTTP=true SEATLOCK_WEBHOOKS_ALLOW_PRIVATE_ADDRESSES=true ./mvnw spring-boot:run
```

The event's organizer is the `X-User-Id` that created it (`2222...` above). Register the receiver for that user before paying, and save the `secret` from the response; it is shown only here, and every `PUT` replaces it:

```
curl -s -X PUT localhost:8080/webhook-endpoint \
  -H 'Content-Type: application/json' -H 'X-User-Id: 22222222-2222-2222-2222-222222222222' \
  -d '{"url":"http://localhost:9000/hook"}'
```

Then pay for a hold on that event as above. The receiver gets a signed `booking.confirmed` within a few seconds.

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

**At-least-once, unordered.** The dispatcher claims due rows with `for update skip locked` and a one-minute lease, and sends outside any transaction. If the app crashes after the receiver answered and before the attempt is recorded, the delivery goes out again. Receivers must dedupe on `Seatlock-Delivery-Id`. Deliveries are not ordered: a `booking.expired` can arrive before an older `booking.confirmed` is retried. Several dispatchers can run at once: `concurrentDispatchersDeliverEachOnce` shows each delivery is sent once, `expiredLeaseIsReclaimed` shows a crashed claim is picked up later, and `sendIsSkippedOnceItsLeaseHasPassed` shows a slow sender does not send what another replica now owns (all in `WebhookDispatcherTest`).

**Dead letters.** `GET /webhook-deliveries?status=PENDING|DELIVERED|DEAD` lists the caller's newest 50 deliveries, and `GET /webhook-deliveries/{id}` adds the payload and attempt log. `POST /webhook-deliveries/{id}/retry` puts a `DEAD` delivery back in the queue; any other status is a 409, and another organizer's delivery is a 404. [`WebhookDeliveryApiTest`](app/src/test/java/dev/seatlock/booking/webhook/WebhookDeliveryApiTest.java) covers the list, the filter, the detail, the 404, the 409 and the retry.

**URL policy.** By default a URL must be https and must resolve only to public addresses. Loopback, any-local, link-local (including `169.254.169.254`), site-local, multicast and IPv6 unique-local addresses are refused. The check runs when the endpoint is registered and again before every send. [`WebhookUrlPolicyTest`](app/src/test/java/dev/seatlock/booking/webhook/WebhookUrlPolicyTest.java) covers the rules, [`WebhookEndpointApiTest`](app/src/test/java/dev/seatlock/booking/webhook/WebhookEndpointApiTest.java) the check at registration, and `blockedAddressIsNotContacted` in `WebhookDispatcherTest` the check before sending. `SEATLOCK_WEBHOOKS_ALLOW_HTTP` and `SEATLOCK_WEBHOOKS_ALLOW_PRIVATE_ADDRESSES` switch the two rules off for local runs.

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
- Settlement holds the payment row lock during the provider call. This is simple and correct, but a slow provider ties up a database connection.
- The outbox keeps per-key order only within one relay batch. Each key carries one message today, so this does not matter yet.
- An outbox row that can never be sent blocks the rows behind it.
- An unexpected settlement failure blocks its Kafka partition until it clears. There is no dead-letter topic.
- The provider lookup in `failUnsettled` can race a charge that is still in flight on a real provider. The fake provider has no such window.
- Webhook sends within one batch are sequential, so one slow receiver delays the rest of its batch.
- The address check runs before the HTTP client resolves the host again, so DNS rebinding can slip past it.
- Webhook secrets are stored in plain text.
- The dead-letter table is a status (`DEAD`) on `webhook_deliveries`, not a separate table.

Every limit above is marked with a `shortcut:` comment in the code that says when to upgrade it, except the fake provider, the missing refunds and the dead-letter status, which is a design choice.

## Design

See [DESIGN.md](DESIGN.md) for the full plan, the non-goals and the remaining milestones.
