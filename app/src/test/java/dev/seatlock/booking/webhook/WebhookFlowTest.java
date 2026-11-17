package dev.seatlock.booking.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.seatlock.booking.IntegrationTest;
import dev.seatlock.booking.MutableClock;
import dev.seatlock.booking.TestTokens;

@IntegrationTest(properties = { "seatlock.expiry.enabled=true", "seatlock.expiry.interval=PT0.2S",
		"seatlock.outbox.enabled=true", "seatlock.settlement.enabled=true", "seatlock.settlement.retry-backoff=PT0.01S",
		"seatlock.webhooks.enabled=true", "seatlock.webhooks.interval=PT0.2S", "seatlock.webhooks.allow-http=true",
		"seatlock.webhooks.allow-private-addresses=true", "seatlock.rate-limit.enabled=false",
		"seatlock.seat-cache.enabled=false" })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class WebhookFlowTest {

	@LocalServerPort
	int port;

	@Autowired
	TestTokens tokens;

	@Autowired
	MutableClock clock;

	private final ObjectMapper json = new ObjectMapper();

	private final UUID organizer = UUID.randomUUID();

	private final UUID buyer = UUID.randomUUID();

	@Test
	void paidBookingNotifiesOrganizer() throws Exception {
		try (TestReceiver receiver = new TestReceiver()) {
			String secret = register(receiver);
			JsonNode hold = holdSeats(1);

			client().post().uri("/holds/{id}/pay", hold.get("id").asText()).header("Authorization", tokens.bearer(buyer))
					.header("Idempotency-Key", UUID.randomUUID().toString()).retrieve().toBodilessEntity();

			await().atMost(Duration.ofSeconds(30)).until(() -> !receiver.requests().isEmpty());
			// Give a wrongful duplicate time to show up before asserting "exactly one".
			Thread.sleep(1000);
			assertThat(receiver.requests()).hasSize(1);
			TestReceiver.Request request = receiver.requests().get(0);
			JsonNode body = json.readTree(request.body());
			assertThat(body.get("type").asText()).isEqualTo("booking.confirmed");
			assertThat(body.get("data").get("holdId").asText()).isEqualTo(hold.get("id").asText());
			assertThat(request.header("Seatlock-Event-Type")).isEqualTo("booking.confirmed");
			assertThat(request.header("Seatlock-Delivery-Id")).isEqualTo(body.get("id").asText());
			String signature = request.header("Seatlock-Signature");
			long t = Long.parseLong(signature.split(",")[0].substring("t=".length()));
			assertThat(signature).isEqualTo(WebhookSigner.signatureHeader(secret, t, request.body()));

			await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
				JsonNode list = json.readTree(client().get().uri("/webhook-deliveries?status=DELIVERED")
						.header("Authorization", tokens.bearer(organizer)).retrieve().body(String.class));
				assertThat(list).hasSize(1);
				assertThat(list.get(0).get("id").asText()).isEqualTo(body.get("id").asText());
			});
		}
	}

	@Test
	void expiredHoldNotifiesOrganizer() throws Exception {
		try (TestReceiver receiver = new TestReceiver()) {
			register(receiver);
			JsonNode hold = holdSeats(2);

			clock.advance(Duration.ofMinutes(10));

			await().atMost(Duration.ofSeconds(30)).until(() -> !receiver.requests().isEmpty());
			Thread.sleep(1000);
			assertThat(receiver.requests()).hasSize(1);
			JsonNode body = json.readTree(receiver.requests().get(0).body());
			assertThat(body.get("type").asText()).isEqualTo("booking.expired");
			assertThat(body.get("data").get("holdId").asText()).isEqualTo(hold.get("id").asText());
			assertThat(body.get("data").get("seatIds")).hasSize(2);
		}
	}

	/** Registers the receiver as the organizer's endpoint and returns the secret. */
	private String register(TestReceiver receiver) throws Exception {
		return json.readTree(client().put().uri("/webhook-endpoint").header("Authorization", tokens.bearer(organizer))
				.body(Map.of("url", receiver.url())).retrieve().body(String.class)).get("secret").asText();
	}

	/** Creates a fresh event for the organizer and holds {@code count} seats for the buyer. */
	private JsonNode holdSeats(int count) throws Exception {
		JsonNode event = json.readTree(client().post().uri("/events").header("Authorization", tokens.bearer(organizer))
				.body(Map.of("name", "Launch Night", "startsAt", clock.instant().plus(Duration.ofDays(30)).toString(),
						"seatCount", count, "priceCents", 2500, "currency", "MYR"))
				.retrieve().body(String.class));
		JsonNode seats = json.readTree(
				client().get().uri("/events/{id}/seats", event.get("id").asText()).retrieve().body(String.class));
		List<String> seatIds = seats.findValuesAsText("id");
		return json.readTree(client().post().uri("/events/{id}/holds", event.get("id").asText())
				.header("Authorization", tokens.bearer(buyer)).body(Map.of("seatIds", seatIds)).retrieve().body(String.class));
	}

	private RestClient client() {
		return RestClient.builder().baseUrl("http://localhost:" + port).build();
	}

}
