package dev.seatlock.booking.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

import dev.seatlock.booking.IntegrationTest;
import dev.seatlock.booking.MutableClock;
import dev.seatlock.booking.TestTokens;
import dev.seatlock.booking.event.Event;
import dev.seatlock.booking.event.EventService;
import dev.seatlock.booking.event.Seat;
import dev.seatlock.booking.hold.HoldService;
import dev.seatlock.booking.hold.SeatsUnavailableException;
import dev.seatlock.booking.payment.Payment;
import dev.seatlock.booking.payment.PaymentService;
import dev.seatlock.booking.settlement.SettlementService;
import dev.seatlock.booking.webhook.WebhookDispatcher;

@IntegrationTest
@AutoConfigureMetrics
class MetricsTest {

	@LocalServerPort
	int port;

	@LocalManagementPort
	int managementPort;

	@Autowired
	EventService events;

	@Autowired
	HoldService holds;

	@Autowired
	PaymentService payments;

	@Autowired
	SettlementService settlement;

	@Autowired
	WebhookDispatcher dispatcher;

	@Autowired
	TestTokens tokens;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MutableClock clock;

	private String scrape() {
		return RestClient.create("http://localhost:" + managementPort).get().uri("/actuator/prometheus").retrieve()
				.body(String.class);
	}

	/** The sample whose name and labels match {@code series}, 0 when it has not been created yet. */
	private double value(String series) {
		Matcher m = Pattern.compile("^" + Pattern.quote(series) + " (\\S+)$", Pattern.MULTILINE).matcher(scrape());
		return m.find() ? Double.parseDouble(m.group(1)) : 0;
	}

	private HttpStatus statusOf(int onPort, String path) {
		return RestClient.create("http://localhost:" + onPort).get().uri(path)
				.exchange((req, res) -> HttpStatus.valueOf(res.getStatusCode().value()));
	}

	@Test
	void holdsAreCountedByOutcome() {
		double created = value("seatlock_holds_total{application=\"seatlock\",outcome=\"created\"}");
		double unavailable = value("seatlock_holds_total{application=\"seatlock\",outcome=\"unavailable\"}");
		Event event = events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), 1, 1000, "MYR",
				UUID.randomUUID());
		List<UUID> seat = List.of(events.listSeats(event.id()).get(0).id());

		holds.createHold(event.id(), UUID.randomUUID(), seat);
		try {
			holds.createHold(event.id(), UUID.randomUUID(), seat);
		}
		catch (SeatsUnavailableException expected) {
		}

		assertThat(value("seatlock_holds_total{application=\"seatlock\",outcome=\"created\"}")).isEqualTo(created + 1);
		assertThat(value("seatlock_holds_total{application=\"seatlock\",outcome=\"unavailable\"}"))
				.isEqualTo(unavailable + 1);
	}

	@Test
	void settledPaymentAndDeliveredWebhookAreCounted() throws IOException {
		HttpServer receiver = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		receiver.createContext("/", ex -> {
			ex.sendResponseHeaders(200, -1);
			ex.close();
		});
		receiver.start();
		try {
			UUID organizer = UUID.randomUUID();
			RestClient.create("http://localhost:" + port).put().uri("/webhook-endpoint")
					.header("Authorization", tokens.bearer(organizer))
					.body(Map.of("url", "http://localhost:" + receiver.getAddress().getPort() + "/")).retrieve()
					.toBodilessEntity();
			Event event = events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), 1, 2500,
					"MYR", organizer);
			UUID buyer = UUID.randomUUID();
			var hold = holds.createHold(event.id(), buyer, events.listSeats(event.id()).stream().map(Seat::id).toList());
			Payment payment = payments.requestPayment(hold.id(), buyer, "k1");
			double settled = value("seatlock_payments_settled_total{application=\"seatlock\",status=\"SUCCEEDED\"}");
			double delivered = value("seatlock_webhook_attempts_total{application=\"seatlock\",outcome=\"delivered\"}");
			assertThat(value("seatlock_webhook_pending{application=\"seatlock\"}")).isZero();

			settlement.settle(payment.id());

			assertThat(value("seatlock_webhook_pending{application=\"seatlock\"}")).isEqualTo(1.0);
			assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
			assertThat(value("seatlock_payments_settled_total{application=\"seatlock\",status=\"SUCCEEDED\"}"))
					.isEqualTo(settled + 1);
			assertThat(value("seatlock_webhook_attempts_total{application=\"seatlock\",outcome=\"delivered\"}"))
					.isEqualTo(delivered + 1);
			assertThat(value("seatlock_webhook_pending{application=\"seatlock\"}")).isZero();
		}
		finally {
			receiver.stop(0);
		}
	}

	@Test
	void outboxBacklogIsAGauge() {
		for (int i = 0; i < 2; i++) {
			jdbc.sql("insert into outbox (id, topic, message_key, payload, created_at) values (?, 't', 'k', '{}', now())")
					.param(UUID.randomUUID()).update();
		}

		assertThat(value("seatlock_outbox_unpublished{application=\"seatlock\"}")).isEqualTo(2.0);
	}

	@Test
	void managementPortExposesOnlyHealthAndPrometheusWithoutAToken() {
		assertThat(statusOf(managementPort, "/actuator/prometheus")).isEqualTo(HttpStatus.OK);
		assertThat(statusOf(managementPort, "/actuator/health")).isEqualTo(HttpStatus.OK);
		assertThat(statusOf(managementPort, "/actuator/env")).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(statusOf(managementPort, "/actuator/beans")).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(statusOf(port, "/actuator/prometheus")).isEqualTo(HttpStatus.NOT_FOUND);
	}

}
