package dev.seatlock.booking.outbox;

import java.time.Instant;
import java.time.ZoneOffset;
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

}
