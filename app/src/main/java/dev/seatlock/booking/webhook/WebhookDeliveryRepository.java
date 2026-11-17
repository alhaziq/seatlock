package dev.seatlock.booking.webhook;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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

	/** A claimed delivery with its endpoint's current url and secret; {@code attempts} already counts this attempt; the claim is ours until {@code leaseUntil}. */
	record Claim(UUID id, String type, String payload, int attempts, String url, String secret, Instant leaseUntil) {
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
				returning d.id, d.type, d.payload, d.attempts, e.url, e.secret, d.next_attempt_at""")
				.params(utc(now), limit, utc(leaseUntil))
				.query((rs, n) -> new Claim(rs.getObject("id", UUID.class), rs.getString("type"),
						rs.getString("payload"), rs.getInt("attempts"), rs.getString("url"), rs.getString("secret"),
						rs.getObject("next_attempt_at", OffsetDateTime.class).toInstant()))
				.list();
	}

	/**
	 * Records the attempt and moves the delivery on: DELIVERED on success, else due again at {@code retryAt}, or
	 * DEAD when {@code retryAt} is null. Does nothing, returning false, when the row was re-claimed or finished since this claim (the
	 * lease end tells a re-claim apart even after a manual retry brings {@code attempts} back to the same number).
	 * Must run in a transaction: the guarded update locks the row, so attempt numbers cannot collide.
	 */
	boolean record(Claim claim, Result result, Instant retryAt) {
		int updated = jdbc.sql("""
				update webhook_deliveries
				   set status = ?, delivered_at = ?, next_attempt_at = coalesce(?, next_attempt_at)
				 where id = ? and attempts = ? and next_attempt_at = ? and status = 'PENDING'""")
				.params(result.succeeded() ? "DELIVERED" : retryAt == null ? "DEAD" : "PENDING",
						result.succeeded() ? utc(result.attemptedAt()) : null,
						result.succeeded() || retryAt == null ? null : utc(retryAt), claim.id(), claim.attempts(),
						utc(claim.leaseUntil()))
				.update();
		if (updated == 0) {
			return false;
		}
		jdbc.sql("""
				insert into webhook_attempts
				       (id, delivery_id, attempt, attempted_at, status_code, error, response_body, duration_ms)
				select ?, ?, coalesce(max(attempt), 0) + 1, ?, ?, ?, ?, ?
				  from webhook_attempts where delivery_id = ?""")
				.params(UUID.randomUUID(), claim.id(), utc(result.attemptedAt()), result.statusCode(),
						withoutNul(result.error()), withoutNul(result.responseBody()), result.durationMs(), claim.id())
				.update();
		return true;
	}

	private static final String DELIVERY_COLUMNS = """
			id, type, hold_id, payload, status, attempts, next_attempt_at, created_at, delivered_at""";

	private static WebhookDelivery delivery(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new WebhookDelivery(rs.getObject("id", UUID.class), rs.getString("type"),
				rs.getObject("hold_id", UUID.class), rs.getString("payload"),
				DeliveryStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
				rs.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
				rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				Optional.ofNullable(rs.getObject("delivered_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant)
						.orElse(null));
	}

	/** The organizer's newest deliveries (at most {@code limit}), optionally only those in {@code status}. */
	List<WebhookDelivery> list(UUID organizerId, DeliveryStatus status, int limit) {
		return jdbc.sql("select " + DELIVERY_COLUMNS + """
				  from webhook_deliveries
				 where organizer_id = ? and (?::text is null or status = ?)
				 order by created_at desc, id
				 limit ?""")
				.params(organizerId, status == null ? null : status.name(), status == null ? null : status.name(), limit)
				.query(WebhookDeliveryRepository::delivery).list();
	}

	Optional<WebhookDelivery> find(UUID organizerId, UUID id) {
		return jdbc.sql("select " + DELIVERY_COLUMNS + " from webhook_deliveries where id = ? and organizer_id = ?")
				.params(id, organizerId).query(WebhookDeliveryRepository::delivery).optional();
	}

	List<WebhookAttempt> attempts(UUID deliveryId) {
		return jdbc.sql("""
				select attempt, attempted_at, status_code, error, response_body, duration_ms
				  from webhook_attempts where delivery_id = ? order by attempt""")
				.param(deliveryId)
				.query((rs, n) -> new WebhookAttempt(rs.getInt("attempt"),
						rs.getObject("attempted_at", OffsetDateTime.class).toInstant(),
						rs.getObject("status_code", Integer.class), rs.getString("error"),
						rs.getString("response_body"), rs.getLong("duration_ms")))
				.list();
	}

	/**
	 * Puts a DEAD delivery back to PENDING, due now, in one conditional update so a concurrent retry cannot
	 * double-apply. Empty when the delivery is missing, someone else's, or not DEAD.
	 */
	Optional<WebhookDelivery> retryDead(UUID organizerId, UUID id, Instant now) {
		return jdbc.sql("""
				update webhook_deliveries
				   set status = 'PENDING', attempts = 0, next_attempt_at = ?
				 where id = ? and organizer_id = ? and status = 'DEAD'
				returning """ + " " + DELIVERY_COLUMNS)
				.params(utc(now), id, organizerId).query(WebhookDeliveryRepository::delivery).optional();
	}

	/** Postgres text cannot hold a NUL character, and a receiver's response may contain one. */
	private static String withoutNul(String s) {
		return s == null ? null : s.replace("\u0000", "");
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
