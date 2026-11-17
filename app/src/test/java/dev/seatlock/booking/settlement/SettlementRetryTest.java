package dev.seatlock.booking.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

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
import dev.seatlock.booking.TestTokens;
import dev.seatlock.booking.event.Event;
import dev.seatlock.booking.event.EventService;
import dev.seatlock.booking.event.Seat;
import dev.seatlock.booking.hold.Hold;
import dev.seatlock.booking.hold.HoldService;
import dev.seatlock.booking.hold.HoldStatus;
import dev.seatlock.booking.payment.Payment;
import dev.seatlock.booking.payment.PaymentStatus;

@IntegrationTest(properties = { "seatlock.expiry.enabled=false", "seatlock.outbox.enabled=true",
		"seatlock.settlement.enabled=true", "seatlock.settlement.retry-backoff=PT1S", "seatlock.rate-limit.enabled=false",
		"seatlock.seat-cache.enabled=false" })
@Import(SettlementRetryTest.FlakyProviderConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class SettlementRetryTest {

	@LocalServerPort
	int port;

	@Autowired
	TestTokens tokens;

	@Autowired
	EventService events;

	@Autowired
	HoldService holds;

	@Autowired
	FakePaymentProvider delegate;

	@Autowired
	FlakyProvider flaky;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MutableClock clock;

	@Test
	void anUnexpectedFailureIsRetriedUntilItClears() {
		flaky.failure = IllegalStateException::new;

		settlesAfterFiveFailures();
	}

	@Test
	void anExceptionSpringKafkaCallsFatalIsAlsoRetriedWithBackoff() {
		flaky.failure = ClassCastException::new;

		UUID paymentId = settlesAfterFiveFailures();

		// Backoff is 1 s. Without it, redelivery here still takes about 500 ms (the consumer's fetch wait),
		// so the 900 ms bound separates the two.
		List<Long> attempts = flaky.attempts.get(paymentId);
		assertThat(attempts).hasSize(6);
		for (int i = 1; i < attempts.size(); i++) {
			assertThat(Duration.ofNanos(attempts.get(i) - attempts.get(i - 1))).isGreaterThan(Duration.ofMillis(900));
		}
	}

	private UUID settlesAfterFiveFailures() {
		Event event = events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), 1, 2500, "MYR", UUID.randomUUID());
		List<UUID> seatIds = events.listSeats(event.id()).stream().map(Seat::id).toList();
		Hold hold = holds.createHold(event.id(), UUID.randomUUID(), seatIds);
		RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();

		UUID paymentId = client.post().uri("/holds/{id}/pay", hold.id())
				.header("Authorization", tokens.bearer(hold.userId()))
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.retrieve().body(Payment.class).id();

		// max-retries is 3, so reaching SUCCEEDED after 5 failures proves the record was not skipped.
		await().atMost(Duration.ofSeconds(15))
				.until(() -> client.get().uri("/payments/{id}", paymentId)
						.header("Authorization", tokens.bearer(hold.userId())).retrieve().body(Payment.class),
						p -> p.status() == PaymentStatus.SUCCEEDED);
		assertThat(holds.getHold(hold.id()).status()).isEqualTo(HoldStatus.CONFIRMED);
		assertThat(jdbc.sql("select count(*) from ledger_entries where payment_id = ?").param(paymentId)
				.query(Integer.class).single()).isEqualTo(2);
		assertThat(delegate.chargesMade(paymentId)).isEqualTo(1);
		return paymentId;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FlakyProviderConfig {

		@Bean
		@Primary
		FlakyProvider flakyProvider(FakePaymentProvider delegate) {
			return new FlakyProvider(delegate);
		}

	}

	/** Throws {@link #failure} on the first 5 charges for each payment id, then delegates; records each attempt's time. */
	static class FlakyProvider implements PaymentProvider {

		private final FakePaymentProvider delegate;

		final Map<UUID, List<Long>> attempts = new ConcurrentHashMap<>();

		volatile Function<String, RuntimeException> failure = IllegalStateException::new;

		FlakyProvider(FakePaymentProvider delegate) {
			this.delegate = delegate;
		}

		@Override
		public ChargeResult charge(UUID paymentId, long amountCents, String currency) {
			List<Long> times = attempts.computeIfAbsent(paymentId, id -> new CopyOnWriteArrayList<>());
			times.add(System.nanoTime());
			if (times.size() <= 5) {
				throw failure.apply("unexpected failure charging " + paymentId);
			}
			return delegate.charge(paymentId, amountCents, currency);
		}

		@Override
		public Optional<ChargeResult> findCharge(UUID paymentId) {
			return delegate.findCharge(paymentId);
		}

	}

}
