package dev.seatlock.booking.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.seatlock.booking.IntegrationTest;
import dev.seatlock.booking.MutableClock;
import dev.seatlock.booking.event.Event;
import dev.seatlock.booking.event.EventService;
import dev.seatlock.booking.event.Seat;
import dev.seatlock.booking.event.SeatStatus;
import dev.seatlock.booking.hold.HoldService;
import dev.seatlock.booking.hold.HoldStatus;
import dev.seatlock.booking.payment.Payment;
import dev.seatlock.booking.payment.PaymentService;
import dev.seatlock.booking.payment.PaymentStatus;

@IntegrationTest
@Timeout(120)
class SettlementTest {

	@Autowired
	EventService events;

	@Autowired
	HoldService holds;

	@Autowired
	PaymentService payments;

	@Autowired
	SettlementService settlement;

	@Autowired
	FakePaymentProvider provider;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MutableClock clock;

	@Test
	void approvedPaymentConfirmsHoldAndBooksSeats() {
		Payment payment = pendingPayment(2500, 2);
		clock.advance(Duration.ofMinutes(1));

		settlement.settle(payment.id());

		assertThat(payments.getPayment(payment.id()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(settledAt(payment.id())).isEqualTo(clock.instant());
		var hold = holds.getHold(payment.holdId());
		assertThat(hold.status()).isEqualTo(HoldStatus.CONFIRMED);
		assertThat(hold.seatIds()).hasSize(2);
		assertThat(events.listSeats(hold.eventId())).extracting(Seat::status)
				.containsExactly(SeatStatus.BOOKED, SeatStatus.BOOKED);
		assertThat(ledger(payment.id())).containsExactlyInAnyOrder("DEBIT PROVIDER_CLEARING 5000 MYR",
				"CREDIT EVENT_PAYABLE 5000 MYR");
	}

	@Test
	void declinedPaymentReleasesTheSeats() {
		Payment payment = pendingPayment(2501, 1);
		UUID eventId = holds.getHold(payment.holdId()).eventId();

		settlement.settle(payment.id());

		Payment settled = payments.getPayment(payment.id());
		assertThat(settled.status()).isEqualTo(PaymentStatus.FAILED);
		assertThat(settled.failureReason()).isEqualTo("INSUFFICIENT_FUNDS");
		assertThat(holds.getHold(payment.holdId()).status()).isEqualTo(HoldStatus.RELEASED);
		List<Seat> seats = events.listSeats(eventId);
		assertThat(seats).extracting(Seat::status).containsExactly(SeatStatus.AVAILABLE);
		assertThat(ledgerCount()).isZero();
		assertThat(holds.createHold(eventId, UUID.randomUUID(), List.of(seats.get(0).id())).status())
				.isEqualTo(HoldStatus.ACTIVE);
	}

	@Test
	void settlingTwiceChargesAndConfirmsOnce() {
		Payment payment = pendingPayment(2500, 2);

		settlement.settle(payment.id());
		settlement.settle(payment.id());

		assertThat(provider.chargesMade(payment.id())).isEqualTo(1);
		assertThat(ledgerCount()).isEqualTo(2);
		assertThat(holds.getHold(payment.holdId()).status()).isEqualTo(HoldStatus.CONFIRMED);
	}

	@Test
	void tenSimultaneousSettlesChargeOnce() {
		Payment payment = pendingPayment(2500, 2);

		CountDownLatch start = new CountDownLatch(1);
		Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < 10; i++) {
				executor.execute(() -> {
					try {
						start.await();
						settlement.settle(payment.id());
					}
					catch (Throwable e) {
						failures.add(e);
					}
				});
			}
			start.countDown();
		}

		assertThat(failures).isEmpty();
		assertThat(provider.chargesMade(payment.id())).isEqualTo(1);
		assertThat(ledgerCount()).isEqualTo(2);
	}

	@Test
	void chargeApprovedBeforeACrashIsNotRepeated() {
		Payment payment = pendingPayment(2500, 2);
		// The charge went through but the settling transaction never committed.
		provider.charge(payment.id(), payment.amountCents(), payment.currency());

		settlement.settle(payment.id());

		assertThat(payments.getPayment(payment.id()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(provider.chargesMade(payment.id())).isEqualTo(1);
		assertThat(ledgerCount()).isEqualTo(2);
	}

	@Test
	void providerTimeoutLeavesEverythingPending() {
		Payment payment = pendingPayment(2502, 1);

		assertThatThrownBy(() -> settlement.settle(payment.id())).isInstanceOf(ProviderTimeoutException.class);

		assertThat(payments.getPayment(payment.id()).status()).isEqualTo(PaymentStatus.PENDING);
		assertThat(holds.getHold(payment.holdId()).status()).isEqualTo(HoldStatus.PAYMENT_PENDING);
		assertThat(ledgerCount()).isZero();
	}

	@Test
	void failUnsettledReleasesTheHold() {
		Payment payment = pendingPayment(2500, 2);
		UUID eventId = holds.getHold(payment.holdId()).eventId();

		settlement.failUnsettled(payment.id(), "PROVIDER_UNAVAILABLE");

		Payment failed = payments.getPayment(payment.id());
		assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
		assertThat(failed.failureReason()).isEqualTo("PROVIDER_UNAVAILABLE");
		assertThat(holds.getHold(payment.holdId()).status()).isEqualTo(HoldStatus.RELEASED);
		assertThat(events.listSeats(eventId)).extracting(Seat::status)
				.containsExactly(SeatStatus.AVAILABLE, SeatStatus.AVAILABLE);
	}

	@Test
	void failUnsettledLeavesASucceededPaymentAlone() {
		Payment payment = pendingPayment(2500, 2);
		settlement.settle(payment.id());

		settlement.failUnsettled(payment.id(), "PROVIDER_UNAVAILABLE");

		Payment after = payments.getPayment(payment.id());
		assertThat(after.status()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThat(after.failureReason()).isNull();
		assertThat(holds.getHold(payment.holdId()).status()).isEqualTo(HoldStatus.CONFIRMED);
		assertThat(ledgerCount()).isEqualTo(2);
	}

	@Test
	void failUnsettledPostsAChargeTheProviderAlreadyApproved() {
		Payment payment = pendingPayment(2500, 2);
		// The charge went through but the settling transaction never committed.
		provider.charge(payment.id(), 5000, "MYR");

		settlement.failUnsettled(payment.id(), "PROVIDER_UNAVAILABLE");

		assertThat(payments.getPayment(payment.id()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
		var hold = holds.getHold(payment.holdId());
		assertThat(hold.status()).isEqualTo(HoldStatus.CONFIRMED);
		assertThat(events.listSeats(hold.eventId())).extracting(Seat::status)
				.containsExactly(SeatStatus.BOOKED, SeatStatus.BOOKED);
		assertThat(ledger(payment.id())).containsExactlyInAnyOrder("DEBIT PROVIDER_CLEARING 5000 MYR",
				"CREDIT EVENT_PAYABLE 5000 MYR");
		assertThat(provider.chargesMade(payment.id())).isEqualTo(1);
	}

	@Test
	void failUnsettledKeepsTheProvidersDeclineReason() {
		Payment payment = pendingPayment(2501, 1);
		UUID eventId = holds.getHold(payment.holdId()).eventId();
		assertThat(provider.charge(payment.id(), 2501, "MYR")).isInstanceOf(ChargeResult.Declined.class);

		settlement.failUnsettled(payment.id(), "PROVIDER_UNAVAILABLE");

		Payment failed = payments.getPayment(payment.id());
		assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
		assertThat(failed.failureReason()).isEqualTo("INSUFFICIENT_FUNDS");
		assertThat(holds.getHold(payment.holdId()).status()).isEqualTo(HoldStatus.RELEASED);
		assertThat(events.listSeats(eventId)).extracting(Seat::status).containsExactly(SeatStatus.AVAILABLE);
		assertThat(ledgerCount()).isZero();
	}

	@Test
	void unknownPaymentIsIgnored() {
		settlement.settle(UUID.randomUUID());
	}

	@Test
	void ledgerAlwaysBalances() {
		for (long price : new long[] { 1000, 2000, 3000, 4000, 5500, 1001, 2001, 3001 }) {
			settlement.settle(pendingPayment(price, 1).id());
		}

		assertThat(jdbc.sql("""
				select payment_id from ledger_entries
				 group by payment_id
				having sum(case when direction = 'DEBIT' then amount_cents else 0 end)
				    <> sum(case when direction = 'CREDIT' then amount_cents else 0 end)""")
				.query(UUID.class).list()).isEmpty();
		assertThat(total("DEBIT")).isEqualTo(15500);
		assertThat(total("CREDIT")).isEqualTo(15500);
		assertThat(jdbc.sql("select count(distinct payment_id) from ledger_entries").query(Integer.class).single())
				.isEqualTo(5);
	}

	private Payment pendingPayment(long priceCents, int seatCount) {
		Event event = events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), seatCount,
				priceCents, "MYR", UUID.randomUUID());
		UUID userId = UUID.randomUUID();
		var hold = holds.createHold(event.id(), userId,
				events.listSeats(event.id()).stream().map(Seat::id).toList());
		return payments.requestPayment(hold.id(), userId, "k1");
	}

	private Instant settledAt(UUID paymentId) {
		return jdbc.sql("select settled_at from payments where id = ?").param(paymentId)
				.query(OffsetDateTime.class).single().toInstant();
	}

	private List<String> ledger(UUID paymentId) {
		return jdbc.sql("select direction, account, amount_cents, currency from ledger_entries where payment_id = ?")
				.param(paymentId)
				.query((rs, i) -> rs.getString("direction") + " " + rs.getString("account") + " "
						+ rs.getLong("amount_cents") + " " + rs.getString("currency"))
				.list();
	}

	private int ledgerCount() {
		return jdbc.sql("select count(*) from ledger_entries").query(Integer.class).single();
	}

	private long total(String direction) {
		return jdbc.sql("select coalesce(sum(amount_cents), 0) from ledger_entries where direction = ?")
				.param(direction).query(Long.class).single();
	}

}
