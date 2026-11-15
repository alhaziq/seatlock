package dev.seatlock.booking.settlement;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class LedgerRepository {

	private final JdbcClient jdbc;

	LedgerRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Double entry: money the provider owes us is debited, money we owe the event organiser is credited. */
	void postPayment(UUID paymentId, UUID eventId, long amountCents, String currency, Instant now) {
		jdbc.sql("""
				insert into ledger_entries (id, payment_id, event_id, account, direction, amount_cents, currency, created_at)
				values (?, ?, ?, 'PROVIDER_CLEARING', 'DEBIT', ?, ?, ?),
				       (?, ?, ?, 'EVENT_PAYABLE', 'CREDIT', ?, ?, ?)""")
				.params(UUID.randomUUID(), paymentId, eventId, amountCents, currency, now.atOffset(ZoneOffset.UTC),
						UUID.randomUUID(), paymentId, eventId, amountCents, currency, now.atOffset(ZoneOffset.UTC))
				.update();
	}

}
