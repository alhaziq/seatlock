# Seatlock

Seatlock is a ticket-booking backend built to stay correct under load. The goal is that 10,000 buyers can compete for 100 seats and exactly 100 of them win: zero oversold, zero double charges. Today the project covers the booking core: events, seats, time-limited holds and hold expiry, on Spring Boot and Postgres.

## Status: milestone 1 of 4

1. **Booking core.** Events, seats, holds, expiry. This is what exists now.
2. **Payments.** Idempotency keys, outbox, Kafka, a worker, a ledger. Not built yet.
3. **Webhooks.** Signed delivery with retries and a dead-letter table. Not built yet.
4. **Proof.** Load test, metrics, public deploy, measured results. Not built yet.

No performance numbers are published. They will arrive with the milestone 4 load test.

## Run it

You need Java 21 and a Docker-compatible runtime. This setup is machine-local: Postgres runs in a container and the app runs on your machine.

```
docker compose up -d
cd booking-api
./mvnw spring-boot:run
```

Open Swagger UI at http://localhost:8080/swagger-ui.html. The OpenAPI JSON is at `/v3/api-docs`.

To try it from the command line, create an event, read its seats, then hold one. Callers identify themselves with an `X-User-Id` header holding a UUID.

```
curl -s -X POST localhost:8080/events -H 'Content-Type: application/json' \
  -d '{"name":"Launch Night","startsAt":"2027-01-01T20:00:00Z","seatCount":10}'
# copy "id" from the response
curl -s localhost:8080/events/<event-id>/seats
# copy the "id" of seat "1"
curl -s -X POST localhost:8080/events/<event-id>/holds \
  -H 'Content-Type: application/json' -H 'X-User-Id: 11111111-1111-1111-1111-111111111111' \
  -d '{"seatIds":["<seat-id>"]}'
```

A hold lasts five minutes. Holding the same seat again returns a conflict.

## How overselling is prevented

A hold request runs in one database transaction. First it runs `select ... for update` on the requested seats that are still `AVAILABLE`, ordered by id. Postgres locks those rows, so a second request for the same seat waits. When the first transaction commits, the waiting request re-checks the row, sees it is no longer `AVAILABLE`, and drops it from its result. The service then compares the number of locked rows with the number of seats asked for. If they differ, it fails the whole request with a conflict and nothing is written. Otherwise it inserts the hold and marks the seats `HELD` in the same transaction. Locking in id order means two requests for overlapping seats take locks in the same order, so they cannot deadlock. Expiry frees a hold and its seats in a single statement.

[`HoldConcurrencyTest`](booking-api/src/test/java/dev/seatlock/booking/hold/HoldConcurrencyTest.java) checks this against a real Postgres. It fires 1,000 parallel requests at 100 seats and asserts that exactly 100 succeed and every seat ends `HELD` by its own hold. A second test sends 200 requests for the same six seats in opposite orders and asserts that exactly one wins with no deadlock.

## Design

See [DESIGN.md](DESIGN.md) for the full plan, the non-goals and the remaining milestones.
