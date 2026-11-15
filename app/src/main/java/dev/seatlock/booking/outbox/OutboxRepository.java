package dev.seatlock.booking.outbox;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

	private final JdbcClient jdbc;

	OutboxRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Call inside the business transaction so the message is stored if and only if the change commits. */
	public void add(String topic, String messageKey, String payload, Instant now) {
		jdbc.sql("""
				insert into outbox (id, topic, message_key, payload, created_at)
				values (?, ?, ?, ?, ?)""")
				.params(UUID.randomUUID(), topic, messageKey, payload, now.atOffset(ZoneOffset.UTC)).update();
	}

	record Row(UUID id, String topic, String messageKey, String payload) {
	}

	/** Locks the oldest unpublished rows; concurrent relays skip rows another relay holds. */
	List<Row> lockUnpublished(int limit) {
		return jdbc.sql("""
				select id, topic, message_key, payload from outbox
				 where published_at is null
				 order by created_at, id
				 limit ?
				   for update skip locked""")
				.param(limit)
				.query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("topic"),
						rs.getString("message_key"), rs.getString("payload")))
				.list();
	}

	void markPublished(List<UUID> ids, Instant now) {
		jdbc.sql("update outbox set published_at = :now where id in (:ids)")
				.param("now", now.atOffset(ZoneOffset.UTC)).param("ids", ids).update();
	}

}
