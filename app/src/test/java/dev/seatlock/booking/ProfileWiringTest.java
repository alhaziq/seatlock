package dev.seatlock.booking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/** The profile files, not test overrides, decide which background components run in each role. */
class ProfileWiringTest {

	private static final String EXPIRY = "holdExpiryJob";

	private static final String RELAY = "outboxRelayJob";

	private static final String LISTENER = "paymentRequestedListener";

	private static final String WEBHOOKS = "webhookDispatchJob";

	@Nested
	@IntegrationTest(properties = { "management.server.port=0", "seatlock.rate-limit.enabled=false",
			"seatlock.seat-cache.enabled=false" })
	@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
	class NoProfile {

		@Autowired
		ApplicationContext context;

		@Test
		void everythingRuns() {
			assertThat(context.containsBean(EXPIRY)).isTrue();
			assertThat(context.containsBean(RELAY)).isTrue();
			assertThat(context.containsBean(LISTENER)).isTrue();
			assertThat(context.containsBean(WEBHOOKS)).isTrue();
		}

	}

	@Nested
	@IntegrationTest(properties = { "management.server.port=0", "seatlock.rate-limit.enabled=false",
			"seatlock.seat-cache.enabled=false" })
	@ActiveProfiles("api")
	@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
	class Api {

		@Autowired
		ApplicationContext context;

		@LocalManagementPort
		int managementPort;

		@Test
		void apiServesManagementAndRelaysButDoesNotSettleOrExpire() {
			assertThat(context.containsBean(RELAY)).isTrue();
			assertThat(context.containsBean(LISTENER)).isFalse();
			assertThat(context.containsBean(EXPIRY)).isFalse();
			assertThat(context.containsBean(WEBHOOKS)).isFalse();
			assertThat(RestClient.create("http://localhost:" + managementPort).get().uri("/actuator/health").retrieve()
					.toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
		}

	}

	@Nested
	@IntegrationTest(properties = { "management.server.port=0", "seatlock.rate-limit.enabled=false",
			"seatlock.seat-cache.enabled=false" })
	@ActiveProfiles("worker")
	@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
	class Worker {

		@Autowired
		ApplicationContext context;

		@Test
		void workerSettlesAndExpiresAndServesAWebServer() {
			assertThat(context.containsBean(LISTENER)).isTrue();
			assertThat(context.containsBean(EXPIRY)).isTrue();
			assertThat(context.containsBean(RELAY)).isFalse();
			assertThat(context.containsBean(WEBHOOKS)).isTrue();
			assertThat(context).isInstanceOf(WebServerApplicationContext.class);
		}

	}

}
