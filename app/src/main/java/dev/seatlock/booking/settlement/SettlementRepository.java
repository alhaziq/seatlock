package dev.seatlock.booking.settlement;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class SettlementRepository {

	private final JdbcClient jdbc;

	SettlementRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record LockedPayment(UUID id, UUID holdId, UUID eventId, String status, long amountCents, String currency) {
	}

	/** Locks the payment row; a concurrent or repeated settlement waits here, then sees the committed status. */
	Optional<LockedPayment> lockPayment(UUID id) {
		return jdbc.sql("""
				select p.id, p.hold_id, h.event_id, p.status, p.amount_cents, p.currency
				  from payments p join holds h on h.id = p.hold_id
				 where p.id = ?
				   for update of p""")
				.param(id)
				.query((rs, i) -> new LockedPayment(rs.getObject("id", UUID.class),
						rs.getObject("hold_id", UUID.class), rs.getObject("event_id", UUID.class),
						rs.getString("status"), rs.getLong("amount_cents"), rs.getString("currency")))
				.optional();
	}

	void markSucceeded(UUID id, Instant now) {
		jdbc.sql("update payments set status = 'SUCCEEDED', settled_at = ? where id = ?")
				.params(utc(now), id).update();
	}

	void markFailed(UUID id, String reason, Instant now) {
		jdbc.sql("update payments set status = 'FAILED', failure_reason = ?, settled_at = ? where id = ?")
				.params(reason, utc(now), id).update();
	}

	void confirmHold(UUID holdId) {
		jdbc.sql("update holds set status = 'CONFIRMED' where id = ? and status = 'PAYMENT_PENDING'")
				.param(holdId).update();
		jdbc.sql("update seats set status = 'BOOKED' where hold_id = ?").param(holdId).update();
	}

	/** Status and hold_id change in one statement, as the seats check constraint requires. */
	void releaseHold(UUID holdId) {
		jdbc.sql("update holds set status = 'RELEASED' where id = ? and status = 'PAYMENT_PENDING'")
				.param(holdId).update();
		jdbc.sql("update seats set status = 'AVAILABLE', hold_id = null where hold_id = ?").param(holdId).update();
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
