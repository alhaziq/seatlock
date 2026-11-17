# Seatlock — design

Working name. A ticket-booking backend that stays correct under load.
Written 2026-11-14. Status: milestones 1-4 built, public deploy pending.

## Why this exists

A portfolio project for backend recruiting. The author is a Java / Spring Boot
developer with a banking background who wants to be hireable both inside and
outside fintech. Bank experience already proves domain knowledge; this project
has to prove what a bank CV usually hides: modern tooling, measured
performance, and something shipped publicly end to end.

The headline a recruiter should be able to repeat:
**"10,000 buyers, 100 seats, zero oversold, zero double charges — here are the numbers."**

## Goals

- Never oversell a seat, proven by an automated concurrency test.
- Never charge twice for one booking, proven by an idempotency test.
- Publicly deployed, with a README that shows architecture, decisions and
  load-test results.
- Hands-on use of Redis, Kafka, Docker, k6 and metrics, each for a reason the
  README can defend.

## Non-goals

- A real frontend. Swagger UI is the demo surface.
- A real payment provider. A fake one simulates delay, decline and timeout.
- Seat maps, pricing tiers, search, email, admin screens.
- Kubernetes, multi-region, service mesh.
- User management beyond simple JWT login.

## Anti-goals (would make it a failure even if it runs)

- A distributed monolith: many small CRUD services calling each other over HTTP.
- Tools added for the keyword with no job to do.
- Claims in the README with no test or measurement behind them.
- Never deployed, or never finished. Each milestone below must be showable alone.

## Approaches considered

| | Approach | Trade-off |
|---|---|---|
| A | Ticket booking under load | Domain-neutral and easy to explain; common project type, so it needs real numbers to stand out |
| B | Webhook delivery platform | Strong infrastructure signal; hard for a non-technical recruiter to grasp |
| C | Ledger / payments API | Deep correctness; reads as "bank developer builds bank thing" |

**Picked: a mix, chosen by the author.** How the mix is cut is my call, not
something agreed in detail:

- **A is the product.** Everything else serves the booking flow.
- **C is the payment step.** A small double-entry ledger records every charge
  and refund. This is where the banking background shows.
- **B is the notification step.** Booking outcomes go out as signed webhooks
  with retries. Kept small: no per-customer rate limits or tenant isolation.

## Shape

One repository, two deployables, split because they scale and fail differently:

- **api** — handles requests. Must be fast and always answer.
- **worker** — consumes events. Talks to slow, unreliable things (payment
  provider, webhook receivers) and retries them.

Both deployables are built from the `app/` codebase and selected by Spring profile.

Shared infrastructure:

- **Postgres** — the source of truth for seats, holds, bookings, payments, ledger.
- **Kafka** — carries booking events from the API to the worker.
- **Redis** — cached seat availability for reads, and per-user rate limiting.
  It is never the source of truth for who holds a seat.

## The flow

1. **Hold.** `POST /events/{id}/holds` reserves seats for 5 minutes. Safety
   comes from a conditional update in Postgres (a seat changes to HELD only if
   it is still AVAILABLE), so two buyers cannot both win.
2. **Pay.** `POST /holds/{id}/pay` with an `Idempotency-Key` header. The API
   records a PENDING payment and an outbox row in one transaction, then
   returns. A relay publishes the outbox row to Kafka.
3. **Settle.** The worker calls the fake payment provider. On success it
   writes balanced ledger entries and confirms the booking. On failure it
   releases the seats. Replayed messages must not charge or confirm twice.
4. **Expire.** The worker releases holds past their expiry time.
5. **Notify.** The worker posts `booking.confirmed` and `booking.expired` to
   the organizer's registered webhook URL, signed with HMAC. Failures retry
   with exponential backoff, then go to a dead-letter table. A delivery log
   is readable through the API. Deliveries are queued in Postgres in the same
   transaction as the outcome rather than sent through Kafka.

## Milestones

Each one ends deployable and worth showing.

1. **Booking core.** Events, seats, holds, expiry, as a single Spring Boot
   app. The concurrency test passes.
2. **Payments.** Idempotency keys, outbox, Kafka, the worker, the ledger.
3. **Webhooks.** Registration, signed delivery, retries, dead-letter, log.
4. **Proof.** k6 load test, metrics dashboard, public deploy, README with numbers.

## Tests that must exist

- 1,000 concurrent hold requests for 100 seats: exactly 100 succeed.
- The same `Idempotency-Key` sent twice: one payment, same response.
- A Kafka message delivered twice: one charge, one confirmation.
- Ledger entries always sum to zero.
- A webhook receiver that fails N times: retried with backoff, then dead-lettered.
- An expired hold: seats return to AVAILABLE.

Integration tests run against real Postgres, Kafka and Redis through
Testcontainers, not mocks.

## Stack

My calls unless marked:

- Java 21 and current Spring Boot (author's choice)
- Postgres with Flyway migrations
- Kafka, Redis
- Testcontainers, JUnit
- Docker Compose for local and for deploy
- GitHub Actions for CI
- k6 for load, Micrometer + Prometheus + Grafana for metrics

## Left to the builder

- Maven or Gradle, package layout, module boundaries inside each deployable.
- Table design beyond the invariants above.
- How the outbox relay runs (polling is fine).
- Hold expiry mechanism (a scheduled sweep is fine).
- Retry counts and backoff timings.

## Open, for the author

- **Name.** "Seatlock" is a placeholder.
- **Where it is hosted and what it may cost.** A single small VM running
  Docker Compose is the default assumption.
- **The GitHub remote.** The repository is not on GitHub yet; CI and the
  image build start once it is pushed.
