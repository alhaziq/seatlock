package dev.seatlock.booking.webhook;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class WebhookDeliveryRepository {

	private final JdbcClient jdbc;

	WebhookDeliveryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Inserts a PENDING delivery for the event's organizer, in the caller's transaction. Inserts nothing when the
	 * event has no organizer or the organizer has no endpoint, or when the hold already has this type.
	 */
	void enqueue(UUID id, UUID eventId, UUID holdId, String type, String payload, Instant now) {
		jdbc.sql("""
				insert into webhook_deliveries
				       (id, endpoint_id, organizer_id, type, hold_id, payload, status, attempts, next_attempt_at, created_at)
				select ?, we.id, we.organizer_id, ?, ?, ?, 'PENDING', 0, ?, ?
				  from events e join webhook_endpoints we on we.organizer_id = e.organizer_id
				 where e.id = ?
				on conflict (type, hold_id) do nothing""")
				.params(id, type, holdId, payload, utc(now), utc(now), eventId).update();
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
