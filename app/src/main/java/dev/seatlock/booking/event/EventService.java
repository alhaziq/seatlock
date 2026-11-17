package dev.seatlock.booking.event;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.seatlock.booking.NotFoundException;

@Service
public class EventService {

	private final JdbcClient jdbc;

	private final Clock clock;

	private final SeatCache seatCache;

	EventService(JdbcClient jdbc, Clock clock, SeatCache seatCache) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.seatCache = seatCache;
	}

	@Transactional
	public Event createEvent(String name, Instant startsAt, int seatCount, long priceCents,
			String currency, UUID organizerId) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into events (id, name, starts_at, price_cents, currency, created_at, organizer_id)
				values (?, ?, ?, ?, ?, ?, ?)""")
				.params(id, name, utc(startsAt), priceCents, currency, utc(clock.instant()), organizerId).update();
		// One statement inserts all seats, labelled "1".."N".
		jdbc.sql("""
				insert into seats (id, event_id, label, status)
				select gen_random_uuid(), ?, n::text, 'AVAILABLE' from generate_series(1, ?) n""")
				.params(id, seatCount).update();
		return getEvent(id);
	}

	public Event getEvent(UUID id) {
		return jdbc.sql("""
				select e.id, e.name, e.starts_at, e.price_cents, e.currency, e.organizer_id,
				       (select count(*) from seats s where s.event_id = e.id) as total,
				       (select count(*) from seats s where s.event_id = e.id and s.status = 'AVAILABLE') as available
				from events e where e.id = ?""")
				.param(id)
				.query((rs, i) -> new Event(rs.getObject("id", UUID.class), rs.getString("name"),
						rs.getObject("starts_at", OffsetDateTime.class).toInstant(), rs.getLong("price_cents"),
						rs.getString("currency"), rs.getInt("total"),
						rs.getInt("available"), rs.getObject("organizer_id", UUID.class)))
				.optional().orElseThrow(() -> new NotFoundException("Event " + id + " not found"));
	}

	public List<Seat> listSeats(UUID eventId) {
		return seatCache.get(eventId, () -> {
			getEvent(eventId);
			return jdbc.sql("select id, label, status from seats where event_id = ? order by label::int")
					.param(eventId)
					.query((rs, i) -> new Seat(rs.getObject("id", UUID.class), rs.getString("label"),
							SeatStatus.valueOf(rs.getString("status"))))
					.list();
		});
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
