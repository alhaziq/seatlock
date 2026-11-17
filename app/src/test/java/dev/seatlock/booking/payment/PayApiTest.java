package dev.seatlock.booking.payment;

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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

import dev.seatlock.booking.IntegrationTest;
import dev.seatlock.booking.MutableClock;
import dev.seatlock.booking.TestTokens;
import dev.seatlock.booking.event.Event;
import dev.seatlock.booking.event.EventService;
import dev.seatlock.booking.event.Seat;
import dev.seatlock.booking.event.SeatStatus;
import dev.seatlock.booking.hold.Hold;
import dev.seatlock.booking.hold.HoldService;
import dev.seatlock.booking.hold.HoldStatus;

@IntegrationTest
class PayApiTest {

	@LocalServerPort
	int port;

	@Autowired
	TestTokens tokens;

	@Autowired
	EventService events;

	@Autowired
	HoldService holds;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MutableClock clock;

	private RestClient client() {
		return RestClient.builder().baseUrl("http://localhost:" + port)
				.defaultStatusHandler(status -> true, (req, res) -> { })
				.build();
	}

	private Event event(int seatCount) {
		return events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), seatCount, 2500, "MYR", UUID.randomUUID());
	}

	private Hold hold(Event event, UUID userId, int seats) {
		List<UUID> seatIds = events.listSeats(event.id()).stream().map(Seat::id).limit(seats).toList();
		return holds.createHold(event.id(), userId, seatIds);
	}

	private RestClient.ResponseSpec pay(UUID holdId, UUID userId, String key) {
		return client().post().uri("/holds/{id}/pay", holdId)
				.header("Authorization", tokens.bearer(userId))
				.header("Idempotency-Key", key)
				.retrieve();
	}

	private int count(String table) {
		return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
	}

	@Test
	void payReturns202PendingAndMovesHoldToPaymentPending() {
		UUID userId = UUID.randomUUID();
		Hold hold = hold(event(3), userId, 2);

		ResponseEntity<Payment> res = pay(hold.id(), userId, "k1").toEntity(Payment.class);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		Payment payment = res.getBody();
		assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
		assertThat(payment.holdId()).isEqualTo(hold.id());
		assertThat(payment.amountCents()).isEqualTo(5000);
		assertThat(payment.currency()).isEqualTo("MYR");
		assertThat(holds.getHold(hold.id()).status()).isEqualTo(HoldStatus.PAYMENT_PENDING);
		List<Map<String, Object>> outbox = jdbc.sql("select topic, message_key, payload, published_at from outbox")
				.query().listOfRows();
		assertThat(outbox).hasSize(1);
		assertThat(outbox.get(0).get("topic")).isEqualTo("payments.requested");
		assertThat(outbox.get(0).get("message_key")).isEqualTo(payment.id().toString());
		assertThat(outbox.get(0).get("payload")).isEqualTo("{\"paymentId\":\"" + payment.id() + "\"}");
		assertThat(outbox.get(0).get("published_at")).isNull();
	}

	@Test
	void sameKeyTwiceReturnsTheSamePayment() {
		UUID userId = UUID.randomUUID();
		Hold hold = hold(event(3), userId, 2);

		ResponseEntity<Payment> first = pay(hold.id(), userId, "k1").toEntity(Payment.class);
		ResponseEntity<Payment> second = pay(hold.id(), userId, "k1").toEntity(Payment.class);

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(second.getBody().id()).isEqualTo(first.getBody().id());
		assertThat(count("payments")).isEqualTo(1);
		assertThat(count("outbox")).isEqualTo(1);
	}

	@Test
	void sameKeyForADifferentHoldIs422() {
		UUID userId = UUID.randomUUID();
		Event event = event(3);
		Hold a = hold(event, userId, 1);
		Hold b = holds.createHold(event.id(), userId, List.of(events.listSeats(event.id()).get(2).id()));
		assertThat(pay(a.id(), userId, "k1").toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

		assertProblem(pay(b.id(), userId, "k1"), HttpStatus.UNPROCESSABLE_CONTENT);
		assertThat(holds.getHold(b.id()).status()).isEqualTo(HoldStatus.ACTIVE);
		assertThat(count("payments")).isEqualTo(1);
	}

	@Test
	void secondKeyForAPayingHoldIs409() {
		UUID userId = UUID.randomUUID();
		Hold hold = hold(event(3), userId, 2);
		assertThat(pay(hold.id(), userId, "k1").toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

		assertProblem(pay(hold.id(), userId, "k2"), HttpStatus.CONFLICT);
		assertThat(count("payments")).isEqualTo(1);
	}

	@Test
	void payingSomeoneElsesHoldIs404() {
		Hold hold = hold(event(3), UUID.randomUUID(), 2);

		assertProblem(pay(hold.id(), UUID.randomUUID(), "k1"), HttpStatus.NOT_FOUND);
		assertThat(holds.getHold(hold.id()).status()).isEqualTo(HoldStatus.ACTIVE);
		assertThat(count("payments")).isEqualTo(0);
	}

	@Test
	void unknownHoldIs404() {
		assertProblem(pay(UUID.randomUUID(), UUID.randomUUID(), "k1"), HttpStatus.NOT_FOUND);
	}

	@Test
	void missingIdempotencyKeyIs400() {
		UUID userId = UUID.randomUUID();
		Hold hold = hold(event(3), userId, 2);

		assertProblem(client().post().uri("/holds/{id}/pay", hold.id()).header("Authorization", tokens.bearer(userId))
				.retrieve(), HttpStatus.BAD_REQUEST);
		assertThat(count("payments")).isEqualTo(0);
	}

	@Test
	void blankIdempotencyKeyIs400() {
		UUID userId = UUID.randomUUID();
		Hold hold = hold(event(3), userId, 2);

		assertProblem(pay(hold.id(), userId, ""), HttpStatus.BAD_REQUEST);
		assertProblem(pay(hold.id(), userId, "   "), HttpStatus.BAD_REQUEST);
		assertThat(count("payments")).isEqualTo(0);
	}

	@Test
	void idempotencyKeyOver255CharsIs400() {
		UUID userId = UUID.randomUUID();
		Hold hold = hold(event(3), userId, 2);

		assertProblem(pay(hold.id(), userId, "k".repeat(256)), HttpStatus.BAD_REQUEST);
		assertThat(count("payments")).isEqualTo(0);
		assertThat(holds.getHold(hold.id()).status()).isEqualTo(HoldStatus.ACTIVE);
	}

	@Test
	void payingAfterTimeRanOutIs409AndHoldIsStillSwept() {
		UUID userId = UUID.randomUUID();
		Hold hold = hold(event(3), userId, 2);
		clock.advance(Duration.ofMinutes(5));

		assertProblem(pay(hold.id(), userId, "k1"), HttpStatus.CONFLICT);
		assertThat(holds.getHold(hold.id()).status()).isEqualTo(HoldStatus.ACTIVE);
		assertThat(holds.releaseExpired()).isEqualTo(1);
	}

	@Test
	void aPayingHoldIsNotExpired() {
		UUID userId = UUID.randomUUID();
		Event event = event(3);
		Hold hold = hold(event, userId, 2);
		assertThat(pay(hold.id(), userId, "k1").toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		clock.advance(Duration.ofMinutes(10));

		assertThat(holds.releaseExpired()).isEqualTo(0);
		assertThat(holds.getHold(hold.id()).status()).isEqualTo(HoldStatus.PAYMENT_PENDING);
		assertThat(events.listSeats(event.id()).stream().map(Seat::status).toList())
				.containsExactly(SeatStatus.HELD, SeatStatus.HELD, SeatStatus.AVAILABLE);
	}

	@Test
	void getPaymentReturnsIt() {
		UUID userId = UUID.randomUUID();
		Hold hold = hold(event(3), userId, 2);
		Payment created = pay(hold.id(), userId, "k1").body(Payment.class);

		ResponseEntity<Payment> res = client().get().uri("/payments/{id}", created.id())
				.header("Authorization", tokens.bearer(userId)).retrieve()
				.toEntity(Payment.class);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(res.getBody()).isEqualTo(created);
	}

	@Test
	void unknownPaymentIs404() {
		assertProblem(client().get().uri("/payments/{id}", UUID.randomUUID())
				.header("Authorization", tokens.bearer(UUID.randomUUID())).retrieve(), HttpStatus.NOT_FOUND);
	}

}
