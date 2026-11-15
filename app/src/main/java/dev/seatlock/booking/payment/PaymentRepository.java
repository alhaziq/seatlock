package dev.seatlock.booking.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PaymentRepository {

	private static final String COLUMNS = "id, hold_id, status, amount_cents, currency, failure_reason";

	private final JdbcClient jdbc;

	PaymentRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Moves the caller's live ACTIVE hold to PAYMENT_PENDING and returns its event id. The row lock makes a
	 * concurrent request wait; under READ COMMITTED it then re-checks status = 'ACTIVE' and claims nothing.
	 */
	Optional<UUID> claimHold(UUID holdId, UUID userId, Instant now) {
		return jdbc.sql("""
				update holds set status = 'PAYMENT_PENDING'
				 where id = ? and user_id = ? and status = 'ACTIVE' and expires_at > ?
				returning event_id""")
				.params(holdId, userId, utc(now)).query(UUID.class).optional();
	}

	boolean holdExists(UUID holdId, UUID userId) {
		return jdbc.sql("select count(*) from holds where id = ? and user_id = ?")
				.params(holdId, userId).query(Integer.class).single() > 0;
	}

	Optional<Payment> findByKey(UUID userId, String idempotencyKey) {
		return jdbc.sql("select " + COLUMNS + " from payments where user_id = ? and idempotency_key = ?")
				.params(userId, idempotencyKey).query(PaymentRepository::map).optional();
	}

	Optional<Payment> findById(UUID id) {
		return jdbc.sql("select " + COLUMNS + " from payments where id = ?")
				.param(id).query(PaymentRepository::map).optional();
	}

	record Price(long amountCents, String currency) {
	}

	/** Price of the hold: its seat count times the event's per-seat price. */
	Price priceOf(UUID holdId, UUID eventId) {
		return jdbc.sql("""
				select e.price_cents * (select count(*) from seats s where s.hold_id = ?) as amount_cents, e.currency
				  from events e where e.id = ?""")
				.params(holdId, eventId)
				.query((rs, i) -> new Price(rs.getLong("amount_cents"), rs.getString("currency"))).single();
	}

	void insert(Payment payment, UUID userId, String idempotencyKey, Instant now) {
		jdbc.sql("""
				insert into payments (id, hold_id, user_id, idempotency_key, amount_cents, currency, status, created_at)
				values (?, ?, ?, ?, ?, ?, ?, ?)""")
				.params(payment.id(), payment.holdId(), userId, idempotencyKey, payment.amountCents(),
						payment.currency(), payment.status().name(), utc(now))
				.update();
	}

	private static Payment map(ResultSet rs, int rowNum) throws SQLException {
		return new Payment(rs.getObject("id", UUID.class), rs.getObject("hold_id", UUID.class),
				PaymentStatus.valueOf(rs.getString("status")), rs.getLong("amount_cents"), rs.getString("currency"),
				rs.getString("failure_reason"));
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
