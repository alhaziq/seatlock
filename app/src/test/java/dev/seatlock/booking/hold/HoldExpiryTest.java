package dev.seatlock.booking.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import dev.seatlock.booking.IntegrationTest;
import dev.seatlock.booking.MutableClock;
import dev.seatlock.booking.event.Event;
import dev.seatlock.booking.event.EventService;
import dev.seatlock.booking.event.Seat;
import dev.seatlock.booking.event.SeatStatus;

@IntegrationTest
@Timeout(120)
class HoldExpiryTest {

	@Autowired
	EventService events;

	@Autowired
	HoldService holds;

	@Autowired
	Clock clock;

	private Event event(int seatCount) {
		return events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), seatCount, 2500, "MYR", UUID.randomUUID());
	}

	private List<Seat> seats(Event event) {
		return events.listSeats(event.id());
	}

	private void advance(Duration duration) {
		((MutableClock) clock).advance(duration);
	}

	@Test
	void holdStillValidIsNotReleased() {
		Event event = event(2);
		holds.createHold(event.id(), UUID.randomUUID(), List.of(seats(event).get(0).id()));

		advance(Duration.ofMinutes(4).plusSeconds(59));

		assertThat(holds.releaseExpired()).isZero();
		assertThat(seats(event).get(0).status()).isEqualTo(SeatStatus.HELD);
	}

	@Test
	void expiredHoldReleasesItsSeats() {
		Event event = event(3);
		List<Seat> seats = seats(event);
		Hold hold = holds.createHold(event.id(), UUID.randomUUID(), List.of(seats.get(0).id(), seats.get(1).id()));

		advance(Duration.ofMinutes(5));

		assertThat(holds.releaseExpired()).isEqualTo(1);
		assertThat(seats(event)).extracting(Seat::status).containsOnly(SeatStatus.AVAILABLE);
		Hold expired = holds.getHold(hold.id());
		assertThat(expired.status()).isEqualTo(HoldStatus.EXPIRED);
		assertThat(expired.seatIds()).isEmpty();
	}

	@Test
	void expiredSeatIsUnavailableUntilSwept() {
		Event event = event(2);
		List<UUID> seat1 = List.of(seats(event).get(0).id());
		holds.createHold(event.id(), UUID.randomUUID(), seat1);

		advance(Duration.ofMinutes(6));

		assertThatThrownBy(() -> holds.createHold(event.id(), UUID.randomUUID(), seat1))
				.isInstanceOf(SeatsUnavailableException.class);
		holds.releaseExpired();
		assertThat(holds.createHold(event.id(), UUID.randomUUID(), seat1).status()).isEqualTo(HoldStatus.ACTIVE);
	}

	@Test
	void secondSweepLeavesANewHoldAlone() {
		Event event = event(2);
		List<UUID> seat1 = List.of(seats(event).get(0).id());
		holds.createHold(event.id(), UUID.randomUUID(), seat1);
		advance(Duration.ofMinutes(6));
		holds.releaseExpired();
		Hold b = holds.createHold(event.id(), UUID.randomUUID(), seat1);

		assertThat(holds.releaseExpired()).isZero();

		Seat seat = seats(event).get(0);
		assertThat(seat.status()).isEqualTo(SeatStatus.HELD);
		assertThat(holds.getHold(b.id()).seatIds()).containsExactly(seat.id());
	}

	@Test
	void sweepsRunningTogetherExpireEachHoldOnce() {
		Event event = event(50);
		for (Seat seat : seats(event)) {
			holds.createHold(event.id(), UUID.randomUUID(), List.of(seat.id()));
		}
		advance(Duration.ofMinutes(6));

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
		assertThat(total.get()).isEqualTo(50);
	}

}
