# Booking Core (Milestone 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A single Spring Boot app where buyers hold seats for 5 minutes, holds expire, and no seat can ever be held twice, proven by a concurrency test.

**Architecture:** One Spring Boot app in `booking-api/`, Postgres as the only store. Seat safety comes from row locks taken in a fixed order inside one transaction, not from application-level locking. A scheduled sweep releases expired holds.

**Tech Stack:** Java 21, latest stable Spring Boot (Web, JDBC, Validation, Actuator), Maven wrapper, Postgres, Flyway, Testcontainers, JUnit 5, AssertJ, springdoc-openapi, GitHub Actions.

**Spec:** `DESIGN.md` (repo root), milestone 1 only.

## Global Constraints

- Java 21. Latest stable Spring Boot at the time of scaffolding; do not pin an older major.
- Postgres is the source of truth. No Redis, Kafka, worker, payments or webhooks in this milestone.
- Integration tests run against real Postgres through Testcontainers, not mocks or H2.
- Hold duration is 5 minutes, configurable as `seatlock.hold-duration` (default `PT5M`).
- Base package `dev.seatlock.booking`. All IDs are UUIDs.
- Data access uses Spring `JdbcClient` with explicit SQL, not JPA. (Plan author's call: the locking SQL is the point of the project and should be visible.)
- All time comes from an injected `java.time.Clock`, never `Instant.now()` or SQL `now()`.
- Errors are returned as Spring `ProblemDetail`.
- The caller is identified by an `X-User-Id` header (a UUID). (Plan author's call: JWT login from the spec is deferred to milestone 4, before public deploy.)
- At most 6 seats per hold; at most 10,000 seats per event. (Plan author's calls.)

## Review Focus

1. Two holds asking for the same seats in opposite order: one gets 201, the other 409, never a deadlock or 500. → Task 3.
2. Duplicate seat IDs inside one hold request: 400, nothing held. → Task 3.
3. Seat IDs that belong to a different event: 409, nothing held. → Task 3.
4. A seat whose hold has expired but not yet been swept: still 409 until the sweep runs, then holdable. → Task 4.
5. The sweep running again after an expired seat was re-held by someone else: the new hold is untouched. → Task 4.

---

## File Structure

```
.gitignore
compose.yaml                         local Postgres
README.md
.github/workflows/ci.yml
booking-api/
  pom.xml, mvnw, .mvn/
  src/main/resources/application.yaml
  src/main/resources/db/migration/V1__booking_core.sql
  src/main/java/dev/seatlock/booking/
    BookingApiApplication.java
    ClockConfig.java                 Clock bean (system UTC)
    ApiExceptionHandler.java         exceptions → ProblemDetail
    NotFoundException.java
    event/  EventController, EventService, EventRepository,
            Event, Seat, SeatStatus, CreateEventRequest
    hold/   HoldController, HoldService, HoldRepository,
            Hold, HoldStatus, CreateHoldRequest,
            SeatsUnavailableException, HoldExpiryJob
  src/test/java/dev/seatlock/booking/
    IntegrationTest.java             shared annotation: Spring Boot test + Postgres container
    MutableClock.java                test Clock with advance(Duration)
    event/EventApiTest.java
    hold/HoldApiTest.java
    hold/HoldConcurrencyTest.java
    hold/HoldExpiryTest.java
```

---

### Task 1: Scaffold, schema and CI

**Files:**
- Create: `.gitignore`, `compose.yaml`, `.github/workflows/ci.yml`
- Create: `booking-api/` scaffold, `application.yaml`, `V1__booking_core.sql`, `ClockConfig.java`
- Test: `booking-api/src/test/java/dev/seatlock/booking/IntegrationTest.java`, `MutableClock.java`, `SchemaTest.java`

**Interfaces:**
- Produces: `@IntegrationTest` annotation (boots the app on a random port with a Testcontainers Postgres via `@ServiceConnection`, sets `seatlock.expiry.enabled=false`, and registers `MutableClock` as the primary `Clock`). `MutableClock.advance(Duration)` and `MutableClock.reset()`.
- Produces: tables `events`, `seats`, `holds` as below.

- [ ] **Step 1: Initialise the repo and scaffold**

Run `git init` in the repo root. Generate a Maven project into `booking-api/` from start.spring.io: group `dev.seatlock`, artifact `booking-api`, package `dev.seatlock.booking`, Java 21, dependencies Web, JDBC, Validation, Actuator, Flyway, PostgreSQL driver, Testcontainers. `.gitignore` covers `target/`, `.idea/`, `*.iml`, `.DS_Store`.

- [ ] **Step 2: Write the failing test `SchemaTest`**

```java
@IntegrationTest
class SchemaTest {
    @Autowired JdbcClient jdbc;

    @Test
    void migrationCreatesBookingTables() {
        List<String> tables = jdbc.sql("""
            select table_name from information_schema.tables
            where table_schema = 'public'""").query(String.class).list();
        assertThat(tables).contains("events", "seats", "holds");
    }
}
```

- [ ] **Step 3: Run it and see it fail**

Run: `cd booking-api && ./mvnw test -Dtest=SchemaTest` (Docker must be running)
Expected: FAIL, tables missing.

- [ ] **Step 4: Write `V1__booking_core.sql`**

```sql
create table events (
    id         uuid primary key,
    name       text not null,
    starts_at  timestamptz not null,
    created_at timestamptz not null
);

create table holds (
    id         uuid primary key,
    event_id   uuid not null references events(id),
    user_id    uuid not null,
    status     text not null check (status in ('ACTIVE', 'EXPIRED')),
    expires_at timestamptz not null,
    created_at timestamptz not null
);
create index holds_active_expiry on holds (expires_at) where status = 'ACTIVE';

create table seats (
    id       uuid primary key,
    event_id uuid not null references events(id),
    label    text not null,
    status   text not null check (status in ('AVAILABLE', 'HELD', 'BOOKED')),
    hold_id  uuid references holds(id),
    unique (event_id, label),
    check ((status = 'AVAILABLE') = (hold_id is null))
);
create index seats_hold on seats (hold_id);
```

- [ ] **Step 5: Add `ClockConfig` (`@Bean Clock clock()` returning `Clock.systemUTC()`), `compose.yaml` (one `postgres` service, database/user/password all `seatlock`, port 5432), and `application.yaml` pointing at it with `seatlock.hold-duration: PT5M`**

- [ ] **Step 6: Run the test and see it pass**

Run: `./mvnw test -Dtest=SchemaTest`
Expected: PASS.

- [ ] **Step 7: Add `.github/workflows/ci.yml`**

On push and pull request: checkout, set up Temurin 21 with Maven cache, run `./mvnw -B verify` in `booking-api`.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "chore: scaffold booking-api with schema and CI"
```

---

### Task 2: Events and seats

**Files:**
- Create: everything under `event/`, `NotFoundException.java`, `ApiExceptionHandler.java`
- Test: `event/EventApiTest.java`

**Interfaces:**
- Produces:
  - `record Event(UUID id, String name, Instant startsAt, int totalSeats, int availableSeats)`
  - `record Seat(UUID id, String label, SeatStatus status)`; `enum SeatStatus { AVAILABLE, HELD, BOOKED }`
  - `EventService.createEvent(String name, Instant startsAt, int seatCount): Event` — seats are labelled `"1"` to `"N"`
  - `EventService.getEvent(UUID id): Event` — throws `NotFoundException`
  - `EventService.listSeats(UUID eventId): List<Seat>` — ordered by numeric label; throws `NotFoundException`
  - `ApiExceptionHandler` maps `NotFoundException` → 404 and bean-validation failures → 400.
- HTTP: `POST /events` body `{name, startsAt, seatCount}` → 201 `Event`; `GET /events/{id}` → 200 `Event`; `GET /events/{id}/seats` → 200 `Seat[]`.

- [ ] **Step 1: Write the failing tests in `EventApiTest`** (use `RestClient` or `TestRestTemplate` against the random port)

```java
@Test void createEventReturns201WithAllSeatsAvailable()
// POST {name:"Launch Night", startsAt:"2027-01-01T20:00:00Z", seatCount:100}
// → 201, totalSeats == 100, availableSeats == 100

@Test void listSeatsReturnsLabelsOneToN()
// create seatCount 3 → GET seats → labels ["1","2","3"], all AVAILABLE

@Test void unknownEventIs404()
// GET /events/{random uuid} → 404; GET /events/{random uuid}/seats → 404

@ParameterizedTest @ValueSource(ints = {0, -1, 10_001})
void invalidSeatCountIs400(int seatCount)

@Test void blankNameIs400()
```

- [ ] **Step 2: Run and see them fail**

Run: `./mvnw test -Dtest=EventApiTest`
Expected: FAIL with 404s (no controller).

- [ ] **Step 3: Implement the `event/` package and the exception handler to the interfaces above**

`createEvent` inserts the event and its seats in one transaction, seats as a single batch insert. `CreateEventRequest` carries the validation: `@NotBlank name`, `@NotNull startsAt`, `@Min(1) @Max(10000) seatCount`.

- [ ] **Step 4: Run and see them pass**

Run: `./mvnw test -Dtest=EventApiTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: create events and list seats"
```

---

### Task 3: Holds that cannot oversell

**Files:**
- Create: `hold/Hold.java`, `HoldStatus.java`, `CreateHoldRequest.java`, `SeatsUnavailableException.java`, `HoldRepository.java`, `HoldService.java`, `HoldController.java`
- Modify: `ApiExceptionHandler.java`
- Test: `hold/HoldApiTest.java`, `hold/HoldConcurrencyTest.java`

**Interfaces:**
- Consumes: `EventService.createEvent`, `EventService.listSeats`, `EventService.getEvent`, `NotFoundException`, `Clock`.
- Produces:
  - `record Hold(UUID id, UUID eventId, UUID userId, HoldStatus status, List<UUID> seatIds, Instant expiresAt)`; `enum HoldStatus { ACTIVE, EXPIRED }`
  - `HoldService.createHold(UUID eventId, UUID userId, List<UUID> seatIds): Hold` — throws `NotFoundException` (unknown event), `SeatsUnavailableException`
  - `HoldService.getHold(UUID id): Hold` — throws `NotFoundException`
  - `ApiExceptionHandler` maps `SeatsUnavailableException` → 409, missing or malformed `X-User-Id` → 400.
- HTTP: `POST /events/{id}/holds` header `X-User-Id`, body `{seatIds: [...]}` → 201 `Hold`; `GET /holds/{id}` → 200 `Hold`.

- [ ] **Step 1: Write the failing tests in `HoldApiTest`**

```java
@Test void holdReturns201AndMarksSeatsHeld()
// event with 3 seats; hold seats 1 and 2 → 201, status ACTIVE,
// expiresAt == clock.instant() + 5 minutes; GET seats → 1,2 HELD, 3 AVAILABLE;
// GET /events/{id} → availableSeats == 1

@Test void holdingAHeldSeatIs409AndHoldsNothing()
// user A holds seat 1; user B requests seats 1 and 2 → 409; seat 2 still AVAILABLE

@Test void duplicateSeatIdsAre400()          // seatIds [s1, s1] → 400, s1 still AVAILABLE
@Test void emptySeatIdsAre400()
@Test void moreThanSixSeatsIs400()
@Test void seatFromAnotherEventIs409()       // nothing held in either event
@Test void unknownEventIs404()
@Test void missingUserHeaderIs400()
@Test void getHoldReturnsItsSeats()
@Test void unknownHoldIs404()
```

- [ ] **Step 2: Write the failing tests in `HoldConcurrencyTest`** (call `HoldService` directly, one virtual thread per request, all released together by a `CountDownLatch`)

```java
@Test void thousandBuyersHundredSeatsExactlyHundredWin() {
    // event with 100 seats; 1,000 tasks, task i requests the single seat (i % 100)
    // with a fresh userId; count successes and SeatsUnavailableException
    assertThat(successes).isEqualTo(100);
    assertThat(unavailable).isEqualTo(900);
    assertThat(otherFailures).isEmpty();
    // every seat is HELD and the 100 hold_ids are distinct
}

@Test void overlappingHoldsInOppositeOrderNeverDeadlock() {
    // event with 6 seats; 200 tasks; even tasks request [s1..s6], odd tasks [s6..s1]
    assertThat(successes).isEqualTo(1);
    assertThat(unavailable).isEqualTo(199);
    assertThat(otherFailures).isEmpty();   // a deadlock would land here
}
```

- [ ] **Step 3: Run both and see them fail**

Run: `./mvnw test -Dtest='Hold*Test'`
Expected: FAIL, classes missing.

- [ ] **Step 4: Implement `createHold` with this algorithm, in one `@Transactional` method**

Reject duplicates in `seatIds` before touching the database (400). Then:

```sql
-- 1. lock the wanted seats in a fixed order; READ COMMITTED re-checks
--    the status after waiting, so a seat taken meanwhile drops out
select id from seats
 where id in (:seatIds) and event_id = :eventId and status = 'AVAILABLE'
 order by id
   for update;
-- 2. fewer rows than requested → throw SeatsUnavailableException (rolls back)
-- 3. insert into holds (..., status 'ACTIVE', expires_at = :now + hold-duration)
-- 4. update seats set status = 'HELD', hold_id = :holdId where id in (:seatIds)
```

`order by id` is what prevents the deadlock; do not replace steps 1 and 4 with a single `update ... where status = 'AVAILABLE'`.

- [ ] **Step 5: Implement `getHold`, the controller, `CreateHoldRequest` (`@NotEmpty @Size(max = 6) seatIds`) and the handler mappings**

- [ ] **Step 6: Run and see them pass**

Run: `./mvnw test -Dtest='Hold*Test'`
Expected: PASS, including both concurrency tests, on three consecutive runs.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: seat holds with ordered row locking"
```

---

### Task 4: Hold expiry

**Files:**
- Create: `hold/HoldExpiryJob.java`
- Modify: `hold/HoldService.java`, `hold/HoldRepository.java`
- Test: `hold/HoldExpiryTest.java`

**Interfaces:**
- Consumes: `HoldService.createHold`, `HoldService.getHold`, `EventService.listSeats`, `MutableClock.advance`.
- Produces:
  - `HoldService.releaseExpired(): int` — marks every ACTIVE hold with `expires_at <= clock.instant()` as EXPIRED, returns its seats to AVAILABLE, and returns the number of holds expired.
  - `HoldExpiryJob` — calls `releaseExpired()` on a fixed delay of `seatlock.expiry.interval` (default `PT5S`); the bean exists only when `seatlock.expiry.enabled` is true (default true).

- [ ] **Step 1: Write the failing tests in `HoldExpiryTest`**

```java
@Test void holdStillValidIsNotReleased()
// hold; advance 4m59s; releaseExpired() == 0; seat HELD

@Test void expiredHoldReleasesItsSeats()
// hold seats 1,2; advance 5m; releaseExpired() == 1;
// seats AVAILABLE; getHold(id).status() == EXPIRED

@Test void expiredSeatIsUnavailableUntilSwept()
// A holds seat 1; advance 6m; B's createHold → SeatsUnavailableException;
// releaseExpired(); B's createHold succeeds

@Test void secondSweepLeavesANewHoldAlone()
// A holds seat 1; advance 6m; releaseExpired(); B holds seat 1;
// releaseExpired() == 0; seat 1 HELD by B's hold

@Test void sweepsRunningTogetherExpireEachHoldOnce()
// 50 expired holds; 4 threads call releaseExpired() at once; results sum to 50
```

- [ ] **Step 2: Run and see them fail**

Run: `./mvnw test -Dtest=HoldExpiryTest`
Expected: FAIL, `releaseExpired` missing.

- [ ] **Step 3: Implement `releaseExpired` as one statement**

```sql
with expired as (
    update holds set status = 'EXPIRED'
     where status = 'ACTIVE' and expires_at <= :now
 returning id
), released as (
    update seats set status = 'AVAILABLE', hold_id = null
     where hold_id in (select id from expired)
)
select count(*) from expired;
```

- [ ] **Step 4: Add `HoldExpiryJob` and `@EnableScheduling`**

- [ ] **Step 5: Run and see them pass, then run the whole suite**

Run: `./mvnw verify`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: release expired holds on a schedule"
```

---

### Task 5: Run it and read about it

**Files:**
- Modify: `booking-api/pom.xml`
- Create: `README.md`
- Test: `booking-api/src/test/java/dev/seatlock/booking/ApiDocsTest.java`

**Interfaces:**
- Consumes: the running app.
- Produces: Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`.

- [ ] **Step 1: Write the failing test**

```java
@Test void openApiListsTheBookingEndpoints()
// GET /v3/api-docs → 200; paths contain "/events", "/events/{id}/seats",
// "/events/{id}/holds", "/holds/{id}"
```

- [ ] **Step 2: Run and see it fail**

Run: `./mvnw test -Dtest=ApiDocsTest`
Expected: FAIL with 404.

- [ ] **Step 3: Add `springdoc-openapi-starter-webmvc-ui`, the release line that matches the Spring Boot major in use**

- [ ] **Step 4: Run and see it pass**

Run: `./mvnw verify`
Expected: PASS.

- [ ] **Step 5: Write `README.md`**

Sections, in order: one-paragraph pitch with the headline from `DESIGN.md`; "Status: milestone 1 of 4" with the four milestones listed; how to run (`docker compose up -d`, `./mvnw spring-boot:run`, open Swagger UI); "How overselling is prevented" explaining the ordered `select ... for update` in five or six sentences with a link to `HoldConcurrencyTest`; link to `DESIGN.md`. No performance numbers yet: those arrive with the milestone 4 load test.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "docs: README and Swagger UI"
```
