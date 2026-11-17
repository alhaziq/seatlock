package dev.seatlock.booking.webhook;

import static dev.seatlock.booking.ProblemJson.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import dev.seatlock.booking.IntegrationTest;
import dev.seatlock.booking.MutableClock;
import dev.seatlock.booking.TestTokens;

@IntegrationTest
class WebhookEndpointApiTest {

	@LocalServerPort
	int port;

	@Autowired
	TestTokens tokens;

	@Autowired
	MutableClock clock;

	private RestClient client() {
		return RestClient.builder().baseUrl("http://localhost:" + port)
				.defaultStatusHandler(status -> true, (req, res) -> { })
				.build();
	}

	private RestClient.ResponseSpec put(UUID user, String url) {
		return client().put().uri("/webhook-endpoint").header("Authorization", tokens.bearer(user))
				.body(Map.of("url", url)).retrieve();
	}

	@Test
	void putCreatesEndpointAndReturnsSecret() {
		Map<?, ?> body = put(UUID.randomUUID(), "https://example.com/hook").body(Map.class);
		assertThat(body.get("url")).isEqualTo("https://example.com/hook");
		assertThat((String) body.get("secret")).matches("^whsec_[A-Za-z0-9_-]{43}$");
	}

	@Test
	void putAgainReplacesUrlAndRotatesSecret() {
		UUID user = UUID.randomUUID();
		Map<?, ?> first = put(user, "https://example.com/a").body(Map.class);
		clock.advance(Duration.ofMinutes(5));
		Map<?, ?> second = put(user, "https://example.com/b").body(Map.class);
		assertThat(second.get("url")).isEqualTo("https://example.com/b");
		assertThat(second.get("secret")).isNotEqualTo(first.get("secret"));
		assertThat(second.get("createdAt")).isEqualTo(first.get("createdAt"));
		assertThat(second.get("updatedAt")).isNotEqualTo(first.get("updatedAt"));
		assertThat(java.time.Instant.parse((String) second.get("updatedAt")))
				.isAfter(java.time.Instant.parse((String) first.get("updatedAt")));
	}

	@Test
	void getNeverReturnsSecret() {
		UUID user = UUID.randomUUID();
		put(user, "https://example.com/hook").toBodilessEntity();
		Map<String, Object> body = client().get().uri("/webhook-endpoint").header("Authorization", tokens.bearer(user)).retrieve()
				.body(Map.class);
		assertThat(body.keySet()).containsExactlyInAnyOrder("url", "createdAt", "updatedAt");
	}

	@Test
	void getWithoutEndpointIs404() {
		assertProblem(client().get().uri("/webhook-endpoint").header("Authorization", tokens.bearer(UUID.randomUUID()))
				.retrieve(), HttpStatus.NOT_FOUND);
	}

	@Test
	void putWithoutTokenIs401() {
		assertProblem(client().put().uri("/webhook-endpoint").body(Map.of("url", "https://example.com/h"))
				.retrieve(), HttpStatus.UNAUTHORIZED);
	}

	@Test
	void putRejectsBlankUrl() {
		assertProblem(put(UUID.randomUUID(), " "), HttpStatus.BAD_REQUEST);
	}

	@Nested
	@IntegrationTest(properties = { "seatlock.expiry.enabled=false", "seatlock.outbox.enabled=false",
			"seatlock.settlement.enabled=false", "seatlock.webhooks.enabled=false",
			"seatlock.webhooks.allow-http=true", "seatlock.webhooks.allow-private-addresses=false" })
	class PrivateAddressesDisallowed {

		@Test
		void putRejectsPrivateAddressWhenNotAllowed() {
			assertProblem(put(UUID.randomUUID(), "https://127.0.0.1/x"), HttpStatus.BAD_REQUEST);
		}

	}

}
