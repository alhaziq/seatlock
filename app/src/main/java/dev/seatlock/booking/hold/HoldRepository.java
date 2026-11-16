package dev.seatlock.booking.hold;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class HoldRepository {

	private final JdbcClient jdbc;

	HoldRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Locks the wanted seats that are still available, in id order so that overlapping requests
	 * cannot deadlock. Under READ COMMITTED a row taken while we waited is re-checked and drops out.
	 */
	List<UUID> lockAvailableSeats(UUID eventId, List<UUID> seatIds) {
		return jdbc.sql("""
				select id from seats
				 where id in (:seatIds) and event_id = :eventId and status = 'AVAILABLE'
				 order by id
				   for update""")
				.param("seatIds", seatIds).param("eventId", eventId)
				.query(UUID.class).list();
	}

	void insertHold(UUID id, UUID eventId, UUID userId, Instant expiresAt, Instant createdAt) {
		jdbc.sql("""
				insert into holds (id, event_id, user_id, status, expires_at, created_at)
				values (?, ?, ?, 'ACTIVE', ?, ?)""")
				.params(id, eventId, userId, utc(expiresAt), utc(createdAt)).update();
	}

	int markSeatsHeld(UUID holdId, List<UUID> seatIds) {
		return jdbc.sql("""
				update seats set status = 'HELD', hold_id = :holdId
				 where id in (:seatIds) and status = 'AVAILABLE'""")
				.param("holdId", holdId).param("seatIds", seatIds).update();
	}

	record ExpiredHold(UUID holdId, UUID eventId, UUID userId, List<UUID> seatIds) {
	}

	/**
	 * One statement, so a hold and its seats expire together; a concurrent sweep waits for the other
	 * transaction, then re-checks status = 'ACTIVE' and leaves rows already expired alone. The seat ids are read
	 * in a CTE, which sees the seats as they were before {@code released} clears them.
	 */
	List<ExpiredHold> expireHolds(Instant now) {
		return jdbc.sql("""
				with expired as (
				    update holds set status = 'EXPIRED'
				     where status = 'ACTIVE' and expires_at <= ?
				 returning id, event_id, user_id
				), held as (
				    select hold_id, array_agg(id order by label::int) as seat_ids
				      from seats where hold_id in (select id from expired) group by hold_id
				), released as (
				    update seats set status = 'AVAILABLE', hold_id = null
				     where hold_id in (select id from expired)
				)
				select e.id, e.event_id, e.user_id, coalesce(h.seat_ids, '{}') as seat_ids
				  from expired e left join held h on h.hold_id = e.id""")
				.param(utc(now))
				.query((rs, i) -> new ExpiredHold(rs.getObject("id", UUID.class),
						rs.getObject("event_id", UUID.class), rs.getObject("user_id", UUID.class),
						List.of((UUID[]) rs.getArray("seat_ids").getArray())))
				.list();
	}

	Optional<Hold> findHold(UUID id) {
		List<UUID> seatIds = jdbc.sql("select id from seats where hold_id = ? order by label::int")
				.param(id).query(UUID.class).list();
		return jdbc.sql("select id, event_id, user_id, status, expires_at from holds where id = ?")
				.param(id)
				.query((rs, i) -> new Hold(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
						rs.getObject("user_id", UUID.class), HoldStatus.valueOf(rs.getString("status")), seatIds,
						rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
				.optional();
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
