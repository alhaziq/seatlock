package dev.seatlock.booking.hold;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.seatlock.booking.IntegrationTest;
import dev.seatlock.booking.event.Event;
import dev.seatlock.booking.event.EventService;
import dev.seatlock.booking.event.Seat;

@IntegrationTest
@Timeout(120)
class HoldConcurrencyTest {

	@Autowired
	EventService events;

	@Autowired
	HoldService holds;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Clock clock;

	private record Outcome(int successes, int unavailable, Queue<Throwable> otherFailures) {
	}

	/** One virtual thread per request; all are released together by a latch. */
	private Outcome race(UUID eventId, int tasks, IntFunction<List<UUID>> seatsForTask) {
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger successes = new AtomicInteger();
		AtomicInteger unavailable = new AtomicInteger();
		Queue<Throwable> otherFailures = new ConcurrentLinkedQueue<>();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < tasks; i++) {
				List<UUID> seatIds = seatsForTask.apply(i);
				executor.execute(() -> {
					try {
						start.await();
						holds.createHold(eventId, UUID.randomUUID(), seatIds);
						successes.incrementAndGet();
					}
					catch (SeatsUnavailableException e) {
						unavailable.incrementAndGet();
					}
					catch (Throwable e) {
						otherFailures.add(e);
					}
				});
			}
			start.countDown();
		}
		return new Outcome(successes.get(), unavailable.get(), otherFailures);
	}

	private Event event(int seatCount) {
		return events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), seatCount, 2500, "MYR");
	}

	private List<UUID> seatIds(Event event) {
		return events.listSeats(event.id()).stream().map(Seat::id).toList();
	}

	@Test
	void thousandBuyersHundredSeatsExactlyHundredWin() {
		Event event = event(100);
		List<UUID> seats = seatIds(event);

		Outcome outcome = race(event.id(), 1_000, i -> List.of(seats.get(i % 100)));

		assertThat(outcome.otherFailures()).isEmpty();
		assertThat(outcome.successes()).isEqualTo(100);
		assertThat(outcome.unavailable()).isEqualTo(900);
		assertThat(jdbc.sql("select status from seats where event_id = ?").param(event.id())
				.query(String.class).list()).hasSize(100).containsOnly("HELD");
		assertThat(jdbc.sql("select count(distinct hold_id) from seats where event_id = ?").param(event.id())
				.query(Integer.class).single()).isEqualTo(100);
	}

	@Test
	void overlappingHoldsInOppositeOrderNeverDeadlock() {
		Event event = event(6);
		List<UUID> ascending = seatIds(event);
		List<UUID> descending = ascending.reversed();

		Outcome outcome = race(event.id(), 200, i -> i % 2 == 0 ? ascending : descending);

		assertThat(outcome.otherFailures()).isEmpty();
		assertThat(outcome.successes()).isEqualTo(1);
		assertThat(outcome.unavailable()).isEqualTo(199);
	}

}
