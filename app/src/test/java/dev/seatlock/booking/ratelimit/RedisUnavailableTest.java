package dev.seatlock.booking.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.web.client.RestClient;

import dev.seatlock.booking.IntegrationTest;
import dev.seatlock.booking.MutableClock;
import dev.seatlock.booking.TestTokens;
import dev.seatlock.booking.event.Event;
import dev.seatlock.booking.event.EventService;
import dev.seatlock.booking.event.Seat;
import dev.seatlock.booking.hold.Hold;

/** Redis is optional: with the limiter and cache on but Redis unreachable, requests behave as if they were off. */
@IntegrationTest(properties = { "management.server.port=0", "seatlock.expiry.enabled=false", "seatlock.outbox.enabled=false",
		"seatlock.settlement.enabled=false", "seatlock.webhooks.enabled=false", "seatlock.rate-limit.enabled=true",
		"seatlock.seat-cache.enabled=true" })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RedisUnavailableTest {

	@DynamicPropertySource
	static void closedRedisPort(DynamicPropertyRegistry registry) throws Exception {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		registry.add("spring.data.redis.port", () -> port);
	}

	@LocalServerPort
	int port;

	@Autowired
	TestTokens tokens;

	@Autowired
	EventService events;

	@Autowired
	MutableClock clock;

	private RestClient client() {
		return RestClient.builder().baseUrl("http://localhost:" + port)
				.defaultStatusHandler(status -> true, (req, res) -> { })
				.build();
	}

	@Test
	void holdsPayAndSeatListSucceedWithoutRedis() {
		Event event = events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), 3, 2500, "MYR",
				UUID.randomUUID());
		UUID user = UUID.randomUUID();

		List<Seat> seats = client().get().uri("/events/{id}/seats", event.id()).retrieve()
				.body(new ParameterizedTypeReference<List<Seat>>() { });
		assertThat(seats).hasSize(3);

		ResponseEntity<Hold> held = client().post().uri("/events/{id}/holds", event.id())
				.header("Authorization", tokens.bearer(user))
				.body(Map.of("seatIds", List.of(seats.get(0).id())))
				.retrieve().toEntity(Hold.class);
		assertThat(held.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		ResponseEntity<Void> paid = client().post().uri("/holds/{id}/pay", held.getBody().id())
				.header("Authorization", tokens.bearer(user)).header("Idempotency-Key", "k1")
				.retrieve().toBodilessEntity();
		assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
	}

}
