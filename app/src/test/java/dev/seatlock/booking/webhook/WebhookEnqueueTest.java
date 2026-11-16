package dev.seatlock.booking.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
import dev.seatlock.booking.payment.Payment;
import dev.seatlock.booking.payment.PaymentService;
import dev.seatlock.booking.settlement.SettlementService;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Timeout(120)
class WebhookEnqueueTest {

	@Autowired
	EventService events;

	@Autowired
	HoldService holds;

	@Autowired
	PaymentService payments;

	@Autowired
	SettlementService settlement;

	@Autowired
	WebhookEndpointRepository endpoints;

	@Autowired
	WebhookEvents webhookEvents;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MutableClock clock;

	private Event event(long price, int seats, boolean withEndpoint) {
		UUID organizer = UUID.randomUUID();
		if (withEndpoint) {
			endpoints.upsert(organizer, "https://example.com/hook", "whsec_x", clock.instant());
		}
		return events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), seats, price, "MYR",
				organizer);
	}

	private Hold hold(Event event, int seats) {
		return holds.createHold(event.id(), UUID.randomUUID(),
				events.listSeats(event.id()).stream().limit(seats).map(Seat::id).toList());
	}

	private Payment pay(Event event, int seats) {
		Hold hold = hold(event, seats);
		return payments.requestPayment(hold.id(), hold.userId(), "k1");
	}

	private List<Map<String, Object>> deliveries() {
		return jdbc.sql("select type, status, attempts, payload, id, next_attempt_at, created_at from webhook_deliveries")
				.query().listOfRows();
	}

	@Test
	void confirmedPaymentEnqueuesOneDelivery() throws Exception {
		Event event = event(1000, 2, true);
		Payment payment = pay(event, 2);

		settlement.settle(payment.id());

		var rows = deliveries();
		assertThat(rows).hasSize(1);
		var row = rows.get(0);
		assertThat(row.get("type")).isEqualTo("booking.confirmed");
		assertThat(row.get("status")).isEqualTo("PENDING");
		assertThat(row.get("attempts")).isEqualTo(0);
		assertThat(row.get("next_attempt_at")).isEqualTo(row.get("created_at"));
		Map<?, ?> payload = JsonMapper.shared().readValue((String) row.get("payload"), Map.class);
		assertThat(payload.get("id")).isEqualTo(row.get("id").toString());
		assertThat(payload.get("type")).isEqualTo("booking.confirmed");
		assertThat(java.time.Instant.parse((String) payload.get("createdAt"))).isEqualTo(clock.instant());
		Map<?, ?> data = (Map<?, ?>) payload.get("data");
		assertThat(data.get("holdId")).isEqualTo(payment.holdId().toString());
		assertThat(data.get("paymentId")).isEqualTo(payment.id().toString());
		assertThat(data.get("amountCents")).isEqualTo(2000);
		assertThat(data.get("currency")).isEqualTo("MYR");
		assertThat(data.get("userId")).isNotNull();
		assertThat(data.get("eventId")).isEqualTo(event.id().toString());
		assertThat(data.get("seatIds"))
				.isEqualTo(events.listSeats(event.id()).stream().map(s -> s.id().toString()).toList());
	}

	@Test
	void settlingTwiceEnqueuesOnce() {
		Payment payment = pay(event(1000, 1, true), 1);

		settlement.settle(payment.id());
		settlement.settle(payment.id());

		assertThat(deliveries()).hasSize(1);
	}

	@Test
	void declinedPaymentEnqueuesNothing() {
		Payment payment = pay(event(1001, 1, true), 1);

		settlement.settle(payment.id());

		assertThat(deliveries()).isEmpty();
	}

	@Test
	void expiredHoldEnqueuesOneDeliveryWithItsSeats() throws Exception {
		Event event = event(1000, 3, true);
		Hold hold = hold(event, 2);
		clock.advance(Duration.ofMinutes(6));

		assertThat(holds.releaseExpired()).isEqualTo(1);

		var rows = deliveries();
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("type")).isEqualTo("booking.expired");
		Map<?, ?> payload = JsonMapper.shared().readValue((String) rows.get(0).get("payload"), Map.class);
		Map<?, ?> data = (Map<?, ?>) payload.get("data");
		assertThat(data.get("holdId")).isEqualTo(hold.id().toString());
		assertThat(data.get("seatIds")).isEqualTo(hold.seatIds().stream().map(UUID::toString).toList());
		assertThat(hold.seatIds()).hasSize(2);
	}

	@Test
	void concurrentSweepsEnqueueOncePerHold() {
		Event event = event(1000, 20, true);
		for (Seat seat : events.listSeats(event.id())) {
			holds.createHold(event.id(), UUID.randomUUID(), List.of(seat.id()));
		}
		clock.advance(Duration.ofMinutes(6));

		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger total = new AtomicInteger();
		Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < 4; i++) {
				executor.execute(() -> {
					try {
						start.await();
						total.addAndGet(holds.releaseExpired());
					}
					catch (Throwable e) {
						failures.add(e);
					}
				});
			}
			start.countDown();
		}

		assertThat(failures).isEmpty();
		assertThat(total.get()).isEqualTo(20);
		assertThat(deliveries()).hasSize(20);
	}

	@Test
	void noEndpointNoDelivery() {
		Event event = event(1000, 2, false);
		settlement.settle(pay(event, 1).id());
		holds.createHold(event.id(), UUID.randomUUID(), List.of(events.listSeats(event.id()).get(1).id()));
		clock.advance(Duration.ofMinutes(6));

		assertThat(holds.releaseExpired()).isEqualTo(1);

		assertThat(deliveries()).isEmpty();
	}

	@Test
	void deliveryIsRolledBackWithTheOutcome() {
		Event event = event(1000, 1, true);
		Hold hold = hold(event, 1);

		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			webhookEvents.bookingExpired(hold.id(), event.id(), hold.userId(), hold.seatIds());
			throw new IllegalStateException("boom");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(deliveries()).isEmpty();
	}

}
