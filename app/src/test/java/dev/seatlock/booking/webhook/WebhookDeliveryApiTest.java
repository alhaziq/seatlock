package dev.seatlock.booking.webhook;

import static dev.seatlock.booking.ProblemJson.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import dev.seatlock.booking.IntegrationTest;
import dev.seatlock.booking.MutableClock;
import dev.seatlock.booking.event.Event;
import dev.seatlock.booking.event.EventService;
import dev.seatlock.booking.event.Seat;
import dev.seatlock.booking.hold.Hold;
import dev.seatlock.booking.hold.HoldService;

@IntegrationTest
class WebhookDeliveryApiTest {

	@LocalServerPort
	int port;

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

	private RestClient client() {
		return RestClient.builder().baseUrl("http://localhost:" + port)
				.defaultStatusHandler(status -> true, (req, res) -> { }).build();
	}

	/** Registers {@code organizer}'s endpoint at {@code url} and creates one PENDING delivery for it. */
	private UUID deliveryFor(UUID organizer, String url) {
		endpoints.upsert(organizer, url, "whsec_delivery_api_test", clock.instant());
		Event event = events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), 1, 1000, "MYR",
				organizer);
		Seat seat = events.listSeats(event.id()).get(0);
		Hold hold = holds.createHold(event.id(), UUID.randomUUID(), List.of(seat.id()));
		tx.executeWithoutResult(s -> webhookEvents.bookingExpired(hold.id(), event.id(), hold.userId(), hold.seatIds()));
		return jdbc.sql("select id from webhook_deliveries where hold_id = ?").param(hold.id()).query(UUID.class)
				.single();
	}

	private RestClient.ResponseSpec get(UUID organizer, String uri) {
		return client().get().uri(uri).header("X-User-Id", organizer.toString()).retrieve();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> rows(Object list) {
		return (List<Map<String, Object>>) list;
	}

	private RestClient.ResponseSpec retry(UUID organizer, UUID id) {
		return client().post().uri("/webhook-deliveries/{id}/retry", id).header("X-User-Id", organizer.toString())
				.retrieve();
	}

	private void deadLetter(TestReceiver receiver) {
		for (int i = 0; i < 6; i++) {
			receiver.respond(500);
		}
		for (int i = 0; i < 6; i++) {
			dispatcher.dispatchBatch();
			clock.advance(Duration.ofHours(1));
		}
	}

	@Test
	void listShowsOnlyCallersDeliveriesNewestFirst() throws Exception {
		try (var receiver = new TestReceiver()) {
			UUID me = UUID.randomUUID();
			UUID first = deliveryFor(me, receiver.url());
			clock.advance(Duration.ofMinutes(1));
			UUID second = deliveryFor(me, receiver.url());
			deliveryFor(UUID.randomUUID(), receiver.url());

			List<Map<String, Object>> body = rows(get(me, "/webhook-deliveries").body(List.class));

			assertThat(body).extracting(d -> d.get("id")).containsExactly(second.toString(),
					first.toString());
			Map<String, Object> row = body.get(0);
			assertThat(row).containsKeys("type", "holdId", "status", "attempts", "nextAttemptAt", "createdAt",
					"deliveredAt");
			assertThat(row.get("status")).isEqualTo("PENDING");
		}
	}

	@Test
	void listFiltersByStatus() throws Exception {
		try (var receiver = new TestReceiver()) {
			UUID me = UUID.randomUUID();
			UUID delivered = deliveryFor(me, receiver.url());
			dispatcher.dispatchBatch();
			clock.advance(Duration.ofMinutes(1));
			UUID pending = deliveryFor(me, receiver.url());

			assertThat(rows(get(me, "/webhook-deliveries?status=DELIVERED").body(List.class)))
					.extracting(d -> d.get("id")).containsExactly(delivered.toString());
			assertThat(rows(get(me, "/webhook-deliveries?status=PENDING").body(List.class)))
					.extracting(d -> d.get("id")).containsExactly(pending.toString());
			assertThat(get(me, "/webhook-deliveries?status=DEAD").body(List.class)).isEmpty();
		}
	}

	@Test
	void listRejectsUnknownStatus() {
		assertProblem(get(UUID.randomUUID(), "/webhook-deliveries?status=BOGUS"), HttpStatus.BAD_REQUEST);
	}

	@Test
	void getShowsPayloadAndAttemptLog() throws Exception {
		try (var receiver = new TestReceiver()) {
			receiver.respond(500);
			UUID me = UUID.randomUUID();
			UUID id = deliveryFor(me, receiver.url());
			dispatcher.dispatchBatch();
			clock.advance(Duration.ofHours(1));
			dispatcher.dispatchBatch();

			Map<?, ?> body = get(me, "/webhook-deliveries/" + id).body(Map.class);

			assertThat(body.get("status")).isEqualTo("DELIVERED");
			assertThat((String) body.get("payload")).contains(id.toString());
			List<Map<String, Object>> log = rows(body.get("attemptLog"));
			assertThat(log).extracting(a -> a.get("attempt")).containsExactly(1, 2);
			assertThat(log).extracting(a -> a.get("statusCode")).containsExactly(500, 200);
			assertThat(log.get(0)).containsKeys("attemptedAt", "error", "responseBody", "durationMs");
		}
	}

	@Test
	void otherOrganizersDeliveryIs404() throws Exception {
		try (var receiver = new TestReceiver()) {
			UUID owner = UUID.randomUUID();
			UUID id = deliveryFor(owner, receiver.url());
			deadLetter(receiver);
			UUID other = UUID.randomUUID();

			assertProblem(get(other, "/webhook-deliveries/" + id), HttpStatus.NOT_FOUND);
			assertProblem(retry(other, id), HttpStatus.NOT_FOUND);
			assertThat(get(owner, "/webhook-deliveries/" + id).body(Map.class).get("status")).isEqualTo("DEAD");
			assertProblem(get(other, "/webhook-deliveries/" + UUID.randomUUID()), HttpStatus.NOT_FOUND);
		}
	}

	@Test
	void retryDeadDeliveryRedelivers() throws Exception {
		try (var receiver = new TestReceiver()) {
			UUID me = UUID.randomUUID();
			UUID id = deliveryFor(me, receiver.url());
			deadLetter(receiver);
			assertThat(get(me, "/webhook-deliveries/" + id).body(Map.class).get("status")).isEqualTo("DEAD");

			Map<?, ?> retried = retry(me, id).body(Map.class);

			assertThat(retried.get("status")).isEqualTo("PENDING");
			assertThat(retried.get("attempts")).isEqualTo(0);
			assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
			Map<?, ?> body = get(me, "/webhook-deliveries/" + id).body(Map.class);
			assertThat(body.get("status")).isEqualTo("DELIVERED");
			assertThat(rows(body.get("attemptLog"))).extracting(a -> a.get("attempt"))
					.containsExactly(1, 2, 3, 4, 5, 6, 7);
		}
	}

	@Test
	void retryNonDeadIs409() throws Exception {
		try (var receiver = new TestReceiver()) {
			UUID me = UUID.randomUUID();
			UUID pending = deliveryFor(me, receiver.url());
			assertProblem(retry(me, pending), HttpStatus.CONFLICT);

			dispatcher.dispatchBatch();
			assertProblem(retry(me, pending), HttpStatus.CONFLICT);
		}
	}

}
