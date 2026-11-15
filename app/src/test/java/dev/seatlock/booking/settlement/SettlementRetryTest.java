package dev.seatlock.booking.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;

import dev.seatlock.booking.IntegrationTest;
import dev.seatlock.booking.MutableClock;
import dev.seatlock.booking.event.Event;
import dev.seatlock.booking.event.EventService;
import dev.seatlock.booking.event.Seat;
import dev.seatlock.booking.hold.Hold;
import dev.seatlock.booking.hold.HoldService;
import dev.seatlock.booking.hold.HoldStatus;
import dev.seatlock.booking.payment.Payment;
import dev.seatlock.booking.payment.PaymentStatus;

@IntegrationTest(properties = { "seatlock.expiry.enabled=false", "seatlock.outbox.enabled=true",
		"seatlock.settlement.enabled=true", "seatlock.settlement.retry-backoff=PT0.01S" })
@Import(SettlementRetryTest.FlakyProviderConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class SettlementRetryTest {

	@LocalServerPort
	int port;

	@Autowired
	EventService events;

	@Autowired
	HoldService holds;

	@Autowired
	FakePaymentProvider delegate;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MutableClock clock;

	@Test
	void anUnexpectedFailureIsRetriedUntilItClears() {
		Event event = events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), 1, 2500, "MYR");
		List<UUID> seatIds = events.listSeats(event.id()).stream().map(Seat::id).toList();
		Hold hold = holds.createHold(event.id(), UUID.randomUUID(), seatIds);
		RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();

		UUID paymentId = client.post().uri("/holds/{id}/pay", hold.id())
				.header("X-User-Id", hold.userId().toString())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.retrieve().body(Payment.class).id();

		// max-retries is 3, so reaching SUCCEEDED after 5 failures proves the record was not skipped.
		await().atMost(Duration.ofSeconds(15))
				.until(() -> client.get().uri("/payments/{id}", paymentId).retrieve().body(Payment.class),
						p -> p.status() == PaymentStatus.SUCCEEDED);
		assertThat(holds.getHold(hold.id()).status()).isEqualTo(HoldStatus.CONFIRMED);
		assertThat(jdbc.sql("select count(*) from ledger_entries where payment_id = ?").param(paymentId)
				.query(Integer.class).single()).isEqualTo(2);
		assertThat(delegate.chargesMade(paymentId)).isEqualTo(1);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FlakyProviderConfig {

		@Bean
		@Primary
		PaymentProvider flakyProvider(FakePaymentProvider delegate) {
			return new FlakyProvider(delegate);
		}

	}

	/** Throws on the first 5 charges for each payment id, then delegates. */
	static class FlakyProvider implements PaymentProvider {

		private final FakePaymentProvider delegate;

		private final Map<UUID, Integer> calls = new ConcurrentHashMap<>();

		FlakyProvider(FakePaymentProvider delegate) {
			this.delegate = delegate;
		}

		@Override
		public ChargeResult charge(UUID paymentId, long amountCents, String currency) {
			if (calls.merge(paymentId, 1, Integer::sum) <= 5) {
				throw new IllegalStateException("unexpected failure charging " + paymentId);
			}
			return delegate.charge(paymentId, amountCents, currency);
		}

		@Override
		public Optional<ChargeResult> findCharge(UUID paymentId) {
			return delegate.findCharge(paymentId);
		}

	}

}
