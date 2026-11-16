package dev.seatlock.booking.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import dev.seatlock.booking.IntegrationTest;
import dev.seatlock.booking.MutableClock;
import dev.seatlock.booking.event.Event;
import dev.seatlock.booking.event.EventService;
import dev.seatlock.booking.event.Seat;
import dev.seatlock.booking.hold.Hold;
import dev.seatlock.booking.hold.HoldService;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Timeout(120)
class WebhookDispatcherTest {

	private static final String SECRET = "whsec_dispatch_test";

	@Autowired
	EventService events;

	@Autowired
	HoldService holds;

	@Autowired
	WebhookEndpointRepository endpoints;

	@Autowired
	WebhookEvents webhookEvents;

	@Autowired
	WebhookDispatcher dispatcher;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MutableClock clock;

	/** Creates an organizer endpoint at {@code url} and {@code count} PENDING deliveries for it. */
	static List<UUID> enqueue(EventService events, HoldService holds, WebhookEndpointRepository endpoints,
			WebhookEvents webhookEvents, TransactionTemplate tx, JdbcClient jdbc, MutableClock clock, String url,
			int count) {
		UUID organizer = UUID.randomUUID();
		endpoints.upsert(organizer, url, SECRET, clock.instant());
		Event event = events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), count, 1000,
				"MYR", organizer);
		for (Seat seat : events.listSeats(event.id())) {
			Hold hold = holds.createHold(event.id(), UUID.randomUUID(), List.of(seat.id()));
			tx.executeWithoutResult(
					s -> webhookEvents.bookingExpired(hold.id(), event.id(), hold.userId(), hold.seatIds()));
		}
		return jdbc.sql("select id from webhook_deliveries where organizer_id = ?").param(organizer)
				.query(UUID.class).list();
	}

	private UUID one(TestReceiver receiver) {
		return enqueue(events, holds, endpoints, webhookEvents, tx, jdbc, clock, receiver.url(), 1).get(0);
	}

	static Map<String, Object> delivery(JdbcClient jdbc, UUID id) {
		return jdbc.sql("select status, attempts, next_attempt_at, delivered_at from webhook_deliveries where id = ?")
				.param(id).query().singleRow();
	}

	static List<Map<String, Object>> attempts(JdbcClient jdbc, UUID id) {
		return jdbc.sql("""
				select attempt, status_code, error, response_body, duration_ms from webhook_attempts
				 where delivery_id = ? order by attempt""").param(id).query().listOfRows();
	}

	private Instant nextAttemptAt(UUID id) {
		return jdbc.sql("select next_attempt_at from webhook_deliveries where id = ?").param(id)
				.query(OffsetDateTime.class).single().toInstant();
	}

	@Test
	void deliversSignedRequest() throws Exception {
		try (var receiver = new TestReceiver()) {
			UUID id = one(receiver);

			assertThat(dispatcher.dispatchBatch()).isEqualTo(1);

			assertThat(delivery(jdbc, id).get("status")).isEqualTo("DELIVERED");
			assertThat(delivery(jdbc, id).get("delivered_at")).isNotNull();
			var rows = attempts(jdbc, id);
			assertThat(rows).hasSize(1);
			assertThat(rows.get(0).get("attempt")).isEqualTo(1);
			assertThat(rows.get(0).get("status_code")).isEqualTo(200);
			assertThat(receiver.requests()).hasSize(1);
			var request = receiver.requests().get(0);
			assertThat(request.header("Content-Type")).startsWith("application/json");
			assertThat(request.header("Seatlock-Delivery-Id")).isEqualTo(id.toString());
			assertThat(request.header("Seatlock-Event-Type")).isEqualTo("booking.expired");
			Matcher sig = Pattern.compile("t=(\\d+),v1=([0-9a-f]{64})").matcher(request.header("Seatlock-Signature"));
			assertThat(sig.matches()).isTrue();
			assertThat(Long.parseLong(sig.group(1))).isEqualTo(clock.instant().getEpochSecond());
			assertThat(sig.group(2)).isEqualTo(WebhookSignerTest.hmacHex(SECRET, sig.group(1) + "." + request.body()));
			Map<?, ?> body = JsonMapper.shared().readValue(request.body(), Map.class);
			assertThat(body.get("id")).isEqualTo(id.toString());
		}
	}

	@Test
	void failsFiveTimesThenSucceeds() throws Exception {
		try (var receiver = new TestReceiver()) {
			for (int i = 0; i < 5; i++) {
				receiver.respond(500);
			}
			UUID id = one(receiver);

			for (int i = 0; i < 5; i++) {
				assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
				Duration backoff = Duration.ofSeconds(10L << i);
				assertThat(nextAttemptAt(id)).isEqualTo(clock.instant().plus(backoff));
				assertThat(delivery(jdbc, id).get("status")).isEqualTo("PENDING");
				clock.advance(backoff.minusSeconds(1));
				assertThat(dispatcher.dispatchBatch()).isZero();
				clock.advance(Duration.ofSeconds(1));
			}
			assertThat(dispatcher.dispatchBatch()).isEqualTo(1);

			assertThat(delivery(jdbc, id).get("status")).isEqualTo("DELIVERED");
			assertThat(delivery(jdbc, id).get("attempts")).isEqualTo(6);
			assertThat(attempts(jdbc, id)).extracting(r -> r.get("status_code"))
					.containsExactly(500, 500, 500, 500, 500, 200);
			assertThat(receiver.requests()).hasSize(6);
		}
	}

	@Test
	void failingReceiverIsDeadLettered() throws Exception {
		try (var receiver = new TestReceiver()) {
			for (int i = 0; i < 10; i++) {
				receiver.respond(500);
			}
			UUID id = one(receiver);

			for (int i = 0; i < 6; i++) {
				assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
				clock.advance(Duration.ofHours(1));
			}

			assertThat(delivery(jdbc, id).get("status")).isEqualTo("DEAD");
			assertThat(attempts(jdbc, id)).hasSize(6);
			clock.advance(Duration.ofDays(1));
			assertThat(dispatcher.dispatchBatch()).isZero();
			assertThat(receiver.requests()).hasSize(6);
		}
	}

	@Test
	void redirectIsAFailure() throws Exception {
		try (var receiver = new TestReceiver()) {
			receiver.respond(302);
			UUID id = one(receiver);

			dispatcher.dispatchBatch();

			assertThat(attempts(jdbc, id)).singleElement().satisfies(r -> assertThat(r.get("status_code")).isEqualTo(302));
			assertThat(delivery(jdbc, id).get("status")).isEqualTo("PENDING");
			assertThat(receiver.requests()).hasSize(1);
		}
	}

	@Test
	void slowReceiverTimesOut() throws Exception {
		try (var receiver = new TestReceiver()) {
			receiver.respond(200, null, Duration.ofSeconds(7));
			UUID id = one(receiver);

			long start = System.nanoTime();
			dispatcher.dispatchBatch();
			Duration took = Duration.ofNanos(System.nanoTime() - start);

			assertThat(took).isLessThan(Duration.ofSeconds(7));
			assertThat(attempts(jdbc, id)).singleElement().satisfies(r -> {
				assertThat(r.get("status_code")).isNull();
				assertThat(r.get("error")).isNotNull();
			});
			assertThat(delivery(jdbc, id).get("status")).isEqualTo("PENDING");
		}
	}

	@Test
	void responseBodyIsTruncated() throws Exception {
		try (var receiver = new TestReceiver()) {
			receiver.respond(500, "x".repeat(5000), Duration.ZERO);
			UUID id = one(receiver);

			dispatcher.dispatchBatch();

			assertThat(attempts(jdbc, id)).singleElement()
					.satisfies(r -> assertThat((String) r.get("response_body")).hasSize(1000));
		}
	}

	@Test
	void concurrentDispatchersDeliverEachOnce() throws Exception {
		try (var receiver = new TestReceiver()) {
			List<UUID> ids = enqueue(events, holds, endpoints, webhookEvents, tx, jdbc, clock, receiver.url(), 50);
			assertThat(ids).hasSize(50);

			CountDownLatch start = new CountDownLatch(1);
			Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
			try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
				for (int i = 0; i < 4; i++) {
					executor.execute(() -> {
						try {
							start.await();
							while (dispatcher.dispatchBatch() > 0) {
							}
						}
						catch (Throwable e) {
							failures.add(e);
						}
					});
				}
				start.countDown();
			}

			assertThat(failures).isEmpty();
			assertThat(receiver.requests()).hasSize(50);
			List<String> received = new ArrayList<>();
			receiver.requests().forEach(r -> received.add(r.header("Seatlock-Delivery-Id")));
			assertThat(received).doesNotHaveDuplicates()
					.containsExactlyInAnyOrderElementsOf(ids.stream().map(UUID::toString).toList());
			assertThat(jdbc.sql("select count(*) from webhook_deliveries where status = 'DELIVERED'").query(Long.class)
					.single()).isEqualTo(50L);
		}
	}

	@Test
	void expiredLeaseIsReclaimed() throws Exception {
		try (var receiver = new TestReceiver()) {
			UUID id = one(receiver);

			assertThat(dispatcher.claim()).hasSize(1);
			assertThat(dispatcher.dispatchBatch()).isZero();
			clock.advance(Duration.ofMinutes(1).plusSeconds(1));
			assertThat(dispatcher.dispatchBatch()).isEqualTo(1);

			assertThat(receiver.requests()).hasSize(1);
			assertThat(delivery(jdbc, id).get("status")).isEqualTo("DELIVERED");
			assertThat(delivery(jdbc, id).get("attempts")).isEqualTo(2);
			assertThat(attempts(jdbc, id)).singleElement().satisfies(r -> assertThat(r.get("attempt")).isEqualTo(1));
		}
	}

	@Test
	void sendIsSkippedOnceItsLeaseHasPassed() throws Exception {
		try (var receiver = new TestReceiver()) {
			UUID id = one(receiver);
			var claims = dispatcher.claim();
			assertThat(claims).hasSize(1);

			clock.advance(Duration.ofMinutes(1));
			assertThat(dispatcher.deliver(claims.get(0))).isFalse();

			assertThat(receiver.requests()).isEmpty();
			assertThat(attempts(jdbc, id)).isEmpty();
			assertThat(delivery(jdbc, id).get("status")).isEqualTo("PENDING");
			assertThat(delivery(jdbc, id).get("attempts")).isEqualTo(1);
			assertThat(nextAttemptAt(id)).isEqualTo(claims.get(0).leaseUntil());
		}
	}

	@Nested
	@IntegrationTest(properties = { "seatlock.expiry.enabled=false", "seatlock.outbox.enabled=false",
			"seatlock.settlement.enabled=false", "seatlock.webhooks.enabled=false",
			"seatlock.webhooks.allow-http=true", "seatlock.webhooks.allow-private-addresses=false" })
	class PrivateAddressesDisallowed {

		@Autowired
		EventService events;

		@Autowired
		HoldService holds;

		@Autowired
		WebhookEndpointRepository endpoints;

		@Autowired
		WebhookEvents webhookEvents;

		@Autowired
		WebhookDispatcher dispatcher;

		@Autowired
		TransactionTemplate tx;

		@Autowired
		JdbcClient jdbc;

		@Autowired
		MutableClock clock;

		@Test
		void blockedAddressIsNotContacted() throws Exception {
			try (var receiver = new TestReceiver()) {
				UUID id = enqueue(events, holds, endpoints, webhookEvents, tx, jdbc, clock, receiver.url(), 1).get(0);
				jdbc.sql("update webhook_endpoints set url = ?")
						.param("http://127.0.0.1:" + receiver.port() + "/hook").update();

				assertThat(dispatcher.dispatchBatch()).isEqualTo(1);

				assertThat(attempts(jdbc, id)).singleElement().satisfies(r -> {
					assertThat(r.get("status_code")).isNull();
					assertThat((String) r.get("error")).contains("url policy");
				});
				assertThat(receiver.requests()).isEmpty();
			}
		}

	}

}
