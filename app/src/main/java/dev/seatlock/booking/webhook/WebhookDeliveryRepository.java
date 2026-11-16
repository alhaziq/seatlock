package dev.seatlock.booking.webhook;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

	/** A claimed delivery with its endpoint's current url and secret; {@code attempts} already counts this attempt. */
	record Claim(UUID id, String type, String payload, int attempts, String url, String secret) {
	}

	/** The outcome of one send: a status code, or an error when no response arrived (or none was attempted). */
	record Result(Integer statusCode, String error, String responseBody, Instant attemptedAt, long durationMs) {

		boolean succeeded() {
			return statusCode != null && statusCode / 100 == 2;
		}

	}

	/**
	 * Claims up to {@code limit} due PENDING deliveries: counts the attempt and leases each row until
	 * {@code leaseUntil}, so a concurrent claimer skips it and a lost sender's row becomes due again.
	 */
	List<Claim> claim(Instant now, Instant leaseUntil, int limit) {
		// shortcut: secrets are stored in plain text, encrypt them at rest before real organizers use it.
		return jdbc.sql("""
				with due as materialized (
				  select id from webhook_deliveries
				   where status = 'PENDING' and next_attempt_at <= ?
				   order by next_attempt_at, id
				   limit ?
				     for update skip locked)
				update webhook_deliveries d
				   set attempts = d.attempts + 1, next_attempt_at = ?
				  from due, webhook_endpoints e
				 where d.id = due.id and e.id = d.endpoint_id
				returning d.id, d.type, d.payload, d.attempts, e.url, e.secret""")
				.params(utc(now), limit, utc(leaseUntil))
				.query((rs, n) -> new Claim(rs.getObject("id", UUID.class), rs.getString("type"),
						rs.getString("payload"), rs.getInt("attempts"), rs.getString("url"), rs.getString("secret")))
				.list();
	}

	/**
	 * Records the attempt and moves the delivery on: DELIVERED on success, else due again at {@code retryAt}, or
	 * DEAD when {@code retryAt} is null. Does nothing when the row was re-claimed or finished since this claim.
	 * Must run in a transaction: the guarded update locks the row, so attempt numbers cannot collide.
	 */
	void record(Claim claim, Result result, Instant retryAt) {
		int updated = jdbc.sql("""
				update webhook_deliveries
				   set status = ?, delivered_at = ?, next_attempt_at = coalesce(?, next_attempt_at)
				 where id = ? and attempts = ? and status = 'PENDING'""")
				.params(result.succeeded() ? "DELIVERED" : retryAt == null ? "DEAD" : "PENDING",
						result.succeeded() ? utc(result.attemptedAt()) : null,
						result.succeeded() || retryAt == null ? null : utc(retryAt), claim.id(), claim.attempts())
				.update();
		if (updated == 0) {
			return;
		}
		jdbc.sql("""
				insert into webhook_attempts
				       (id, delivery_id, attempt, attempted_at, status_code, error, response_body, duration_ms)
				select ?, ?, coalesce(max(attempt), 0) + 1, ?, ?, ?, ?, ?
				  from webhook_attempts where delivery_id = ?""")
				.params(UUID.randomUUID(), claim.id(), utc(result.attemptedAt()), result.statusCode(), result.error(),
						result.responseBody(), result.durationMs(), claim.id())
				.update();
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
