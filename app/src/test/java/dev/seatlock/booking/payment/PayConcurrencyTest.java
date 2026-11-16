package dev.seatlock.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.seatlock.booking.IntegrationTest;
import dev.seatlock.booking.event.Event;
import dev.seatlock.booking.event.EventService;
import dev.seatlock.booking.event.Seat;
import dev.seatlock.booking.hold.Hold;
import dev.seatlock.booking.hold.HoldService;

@IntegrationTest
@Timeout(120)
class PayConcurrencyTest {

	@Autowired
	EventService events;

	@Autowired
	HoldService holds;

	@Autowired
	PaymentService payments;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Clock clock;

	@Test
	void twentySimultaneousPaysWithOneKeyMakeOnePayment() {
		Event event = events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), 3, 2500, "MYR", UUID.randomUUID());
		UUID userId = UUID.randomUUID();
		Hold hold = holds.createHold(event.id(), userId,
				events.listSeats(event.id()).stream().map(Seat::id).limit(2).toList());

		CountDownLatch start = new CountDownLatch(1);
		Set<UUID> distinctPaymentIds = ConcurrentHashMap.newKeySet();
		Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < 20; i++) {
				executor.execute(() -> {
					try {
						start.await();
						distinctPaymentIds.add(payments.requestPayment(hold.id(), userId, "k1").id());
					}
					catch (Throwable e) {
						failures.add(e);
					}
				});
			}
			start.countDown();
		}

		assertThat(distinctPaymentIds).hasSize(1);
		assertThat(failures).isEmpty();
		assertThat(jdbc.sql("select count(*) from payments").query(Integer.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from outbox").query(Integer.class).single()).isEqualTo(1);
	}

}
