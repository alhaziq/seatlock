package dev.seatlock.booking.webhook;

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
public class WebhookEndpointRepository {

	private static final String COLUMNS = "id, organizer_id, url, secret, created_at, updated_at";

	private final JdbcClient jdbc;

	WebhookEndpointRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Creates the organizer's endpoint or replaces its url and secret; created_at is kept on replace. */
	WebhookEndpoint upsert(UUID organizerId, String url, String secret, Instant now) {
		return jdbc.sql("""
				insert into webhook_endpoints (id, organizer_id, url, secret, created_at, updated_at)
				values (?, ?, ?, ?, ?, ?)
				on conflict (organizer_id) do update
				  set url = excluded.url, secret = excluded.secret, updated_at = excluded.updated_at
				returning """ + " " + COLUMNS)
				.params(UUID.randomUUID(), organizerId, url, secret, utc(now), utc(now))
				.query(WebhookEndpointRepository::map).single();
	}

	public Optional<WebhookEndpoint> findByOrganizer(UUID organizerId) {
		return jdbc.sql("select " + COLUMNS + " from webhook_endpoints where organizer_id = ?")
				.param(organizerId).query(WebhookEndpointRepository::map).optional();
	}

	private static OffsetDateTime utc(Instant i) {
		return i.atOffset(ZoneOffset.UTC);
	}

	private static WebhookEndpoint map(ResultSet rs, int row) throws SQLException {
		return new WebhookEndpoint(rs.getObject("id", UUID.class), rs.getObject("organizer_id", UUID.class),
				rs.getString("url"), rs.getString("secret"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				rs.getObject("updated_at", OffsetDateTime.class).toInstant());
	}

}
