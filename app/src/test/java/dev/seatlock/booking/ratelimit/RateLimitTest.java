package dev.seatlock.booking.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;

import dev.seatlock.booking.IntegrationTest;
import dev.seatlock.booking.MutableClock;
import dev.seatlock.booking.TestTokens;
import dev.seatlock.booking.event.Event;
import dev.seatlock.booking.event.EventService;
import dev.seatlock.booking.event.Seat;

// Fresh user ids and usernames per test keep these independent of whatever is already in Redis.
@IntegrationTest(properties = { "seatlock.expiry.enabled=false", "seatlock.outbox.enabled=false",
		"seatlock.settlement.enabled=false", "seatlock.webhooks.enabled=false", "seatlock.rate-limit.enabled=true",
		"seatlock.seat-cache.enabled=false" })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RateLimitTest {

	@LocalServerPort
	int port;

	@Autowired
	TestTokens tokens;

	@Autowired
	EventService events;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MutableClock clock;

	private RestClient client() {
		return RestClient.builder().baseUrl("http://localhost:" + port)
				.defaultStatusHandler(status -> true, (req, res) -> { })
				.build();
	}

	private ResponseEntity<String> hold(UUID eventId, UUID userId, UUID seatId) {
		return client().post().uri("/events/{id}/holds", eventId)
				.header("Authorization", tokens.bearer(userId))
				.body(Map.of("seatIds", List.of(seatId)))
				.retrieve().toEntity(String.class);
	}

	private ResponseEntity<String> login(String username) {
		return client().post().uri("/auth/login").body(Map.of("username", username, "password", "correct horse"))
				.retrieve().toEntity(String.class);
	}

	@Test
	void eleventhHoldInTenSecondsIs429WithRetryAfterAndChangesNothing() {
		Event event = events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), 20, 2500, "MYR",
				UUID.randomUUID());
		List<UUID> seats = events.listSeats(event.id()).stream().map(Seat::id).toList();
		UUID user = UUID.randomUUID();
		for (int i = 0; i < 10; i++) {
			assertThat(hold(event.id(), user, seats.get(i)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
		}

		ResponseEntity<String> limited = hold(event.id(), user, seats.get(10));

		assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(limited.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(Long.parseLong(limited.getHeaders().getFirst("Retry-After"))).isBetween(1L, 10L);
		assertThat(jdbc.sql("select count(*) from holds").query(Integer.class).single()).isEqualTo(10);
		assertThat(jdbc.sql("select count(*) from seats where status = 'AVAILABLE'").query(Integer.class).single())
				.isEqualTo(10);

		// a different user is unaffected
		assertThat(hold(event.id(), UUID.randomUUID(), seats.get(10)).getStatusCode()).isEqualTo(HttpStatus.CREATED);

		// and the limited user can hold again once the window has passed
		clock.advance(Duration.ofSeconds(10));
		assertThat(hold(event.id(), user, seats.get(11)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void eleventhLoginInAMinuteIs429EvenWithTheRightPassword() {
		String username = "user_" + UUID.randomUUID().toString().substring(0, 8).replace('-', 'x');
		client().post().uri("/auth/signup").body(Map.of("username", username, "password", "correct horse"))
				.retrieve().toBodilessEntity();
		for (int i = 0; i < 10; i++) {
			assertThat(login(username).getStatusCode()).isEqualTo(HttpStatus.OK);
		}

		ResponseEntity<String> limited = login(username);

		assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(Long.parseLong(limited.getHeaders().getFirst("Retry-After"))).isBetween(1L, 60L);
	}

}
