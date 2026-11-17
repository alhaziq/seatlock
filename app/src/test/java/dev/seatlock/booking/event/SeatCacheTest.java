package dev.seatlock.booking.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;

import dev.seatlock.booking.IntegrationTest;
import dev.seatlock.booking.MutableClock;
import dev.seatlock.booking.hold.HoldService;

// A fresh event id per test means a fresh cache key, whatever Redis already holds.
@IntegrationTest(properties = { "management.server.port=0", "seatlock.expiry.enabled=false", "seatlock.outbox.enabled=false",
		"seatlock.settlement.enabled=false", "seatlock.webhooks.enabled=false", "seatlock.rate-limit.enabled=false",
		"seatlock.seat-cache.enabled=true" })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeatCacheTest {

	@LocalServerPort
	int port;

	@Autowired
	EventService events;

	@Autowired
	HoldService holds;

	@Autowired
	MutableClock clock;

	private List<Seat> seats(UUID eventId) {
		return RestClient.create("http://localhost:" + port).get().uri("/events/{id}/seats", eventId).retrieve()
				.body(new ParameterizedTypeReference<List<Seat>>() { });
	}

	@Test
	void secondReadIsServedFromRedisUntilTheTtlPasses() {
		Event event = events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), 2, 2500, "MYR",
				UUID.randomUUID());
		Seat first = seats(event.id()).get(0);

		// changes the seat in SQL behind the cache's back
		holds.createHold(event.id(), UUID.randomUUID(), List.of(first.id()));

		assertThat(seats(event.id())).extracting(Seat::status).containsOnly(SeatStatus.AVAILABLE);
		await().atMost(Duration.ofSeconds(3)).untilAsserted(
				() -> assertThat(seats(event.id())).extracting(Seat::status).contains(SeatStatus.HELD));
	}

}
