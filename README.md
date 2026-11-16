# Seatlock

Seatlock is a ticket-booking backend built to stay correct under load. The goal is that 10,000 buyers can compete for 100 seats and exactly 100 of them win: zero oversold, zero double charges. Today the project covers the booking core (events, seats, time-limited holds and hold expiry) and payments (idempotent pay requests, an outbox, Kafka, a settlement worker and a ledger), on Spring Boot, Postgres and Kafka.

## Status: milestone 2 of 4

1. **Booking core.** Events, seats, holds, expiry. Built.
2. **Payments.** Idempotency keys, outbox, Kafka, a worker, a ledger. Built, against a fake payment provider.
3. **Webhooks.** Signed delivery with retries and a dead-letter table. Not built yet.
4. **Proof.** Load test, metrics, public deploy, measured results. Not built yet.

No performance numbers are published. They will arrive with the milestone 4 load test.

## Run it

You need Java 21 and Docker with the Compose plugin. This setup is machine-local: Postgres and Kafka run in containers and the app runs on your machine.

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

A hold lasts five minutes. Holding the same seat again returns a conflict. Paying again with the same `Idempotency-Key` on the same hold returns the same payment. Use a new key for each new hold: a key is tied to the user and the hold it was first used on.

The payment provider is a fake. Its result depends on the amount in cents:

| Amount ends in | Result |
|----------------|--------|
| `01` | Declined with `INSUFFICIENT_FUNDS`. The payment is `FAILED` and the seats are freed. |
| `02` | Times out. Settlement retries a few times, then fails the payment with `PROVIDER_UNAVAILABLE` and frees the seats. |
| anything else | Approved. The payment is `SUCCEEDED` and the hold is `CONFIRMED`. |

## How overselling is prevented

A hold request runs in one database transaction. First it runs `select ... for update` on the requested seats that are still `AVAILABLE`, ordered by id. Postgres locks those rows, so a second request for the same seat waits. When the first transaction commits, the waiting request re-checks the row, sees it is no longer `AVAILABLE`, and drops it from its result. The service then compares the number of locked rows with the number of seats asked for. If they differ, it fails the whole request with a conflict and nothing is written. Otherwise it inserts the hold and marks the seats `HELD` in the same transaction. The code locks rows in id order, so every transaction takes its locks in the same order. Expiry frees a hold and its seats in a single statement.

[`HoldConcurrencyTest`](app/src/test/java/dev/seatlock/booking/hold/HoldConcurrencyTest.java) checks this against a real Postgres. It makes 1,000 concurrent calls to the hold service (not HTTP requests) for 100 seats, on virtual threads, through the default database connection pool. It asserts that exactly 100 succeed and every seat ends `HELD` by its own hold. A second test makes 200 overlapping hold attempts for the same six seats in opposite seat orders. It asserts that exactly one wins, with no deadlock and no server error.

## How double charging is prevented

Paying a hold has several steps, and each one can be repeated: the client can retry, Kafka can deliver a message twice and a worker can crash halfway. Five layers keep the money moving once.

1. **The idempotency key on the pay request.** `POST /holds/{id}/pay` requires an `Idempotency-Key` header. The service first claims the hold, which moves it to `PAYMENT_PENDING`, then looks up the key. A simultaneous request with the same key waits on the hold row, then finds the first request's payment and returns it. The same key on a different hold is a 422. A different key on a hold that is already being paid is a 409. [`PayConcurrencyTest`](app/src/test/java/dev/seatlock/booking/payment/PayConcurrencyTest.java) sends 20 simultaneous calls with one key and asserts one payment and one outbox row. [`PayApiTest`](app/src/test/java/dev/seatlock/booking/payment/PayApiTest.java) covers the replay and the two error cases.
2. **The outbox, written in the same transaction as the payment.** The service inserts the payment and an `outbox` row together, so a payment never exists without its message and a message never exists without its payment. `OutboxRelay` reads unpublished rows with `for update skip locked` and publishes them to the Kafka topic `payments.requested`, keyed by payment id. [`OutboxRelayTest`](app/src/test/java/dev/seatlock/booking/outbox/OutboxRelayTest.java) checks that published rows are not sent again and that four relays running together publish each of 200 rows once.
3. **At-least-once delivery.** The relay can crash after sending and before marking a row published, and Kafka can redeliver. So the consumer must expect each message more than once. It commits an offset only after the payment is settled, skipped or given up on. [`PaymentFlowTest`](app/src/test/java/dev/seatlock/booking/settlement/PaymentFlowTest.java) sends a duplicate message for a settled payment and asserts one charge and two ledger entries.
4. **The row lock and status check in settlement.** `SettlementService.settle` locks the payment row, returns if the status is not `PENDING`, and otherwise charges and records the result in one transaction. A second `settle` waits for the lock, then sees the new status and stops. [`SettlementTest`](app/src/test/java/dev/seatlock/booking/settlement/SettlementTest.java) settles one payment from ten threads at once and asserts one charge and two ledger entries. A success writes two balanced ledger entries and confirms the hold. A decline marks the payment failed and releases the hold.
5. **The payment id as the provider's idempotency key.** The charge can succeed and the app can crash before the transaction commits. On the retry the lock and status check pass, because the payment is still `PENDING`. The provider has already seen this payment id, so it returns the earlier result and moves no money. `SettlementTest` covers this: a charge made before a crash is not repeated. When settlement gives up after repeated provider timeouts, `failUnsettled` asks the provider whether the charge went through, and completes it if so. `SettlementTest` covers that too.

Retry behaviour is set in `SettlementKafkaConfig` and tested in [`SettlementRetryTest`](app/src/test/java/dev/seatlock/booking/settlement/SettlementRetryTest.java) and `PaymentFlowTest`. Unreadable messages are skipped at once. A provider timeout is retried a bounded number of times, then the payment is failed. Any other failure is retried until it clears, because skipping a real payment would strand its seats. [`ProfileWiringTest`](app/src/test/java/dev/seatlock/booking/ProfileWiringTest.java) checks which components each profile starts.

### Known limits

- The payment provider is a fake. There is no real provider integration.
- There are no refunds.
- Settlement holds the payment row lock during the provider call. This is simple and correct, but a slow provider ties up a database connection.
- The outbox keeps per-key order only within one relay batch. Each key carries one message today, so this does not matter yet.
- An outbox row that can never be sent blocks the rows behind it.
- An unexpected settlement failure blocks its Kafka partition until it clears. There is no dead-letter topic.
- The provider lookup in `failUnsettled` can race a charge that is still in flight on a real provider. The fake provider has no such window.

Each of these is marked with a `shortcut:` comment in the code that says when to upgrade it.

## Design

See [DESIGN.md](DESIGN.md) for the full plan, the non-goals and the remaining milestones.
