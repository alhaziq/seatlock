package dev.seatlock.booking.metrics;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/** Row counts of the two queues, read from the database on each scrape. */
@Component
class BacklogGauges {

	BacklogGauges(MeterRegistry registry, JdbcClient jdbc) {
		gauge(registry, jdbc, "seatlock.outbox.unpublished", "select count(*) from outbox where published_at is null");
		gauge(registry, jdbc, "seatlock.webhook.pending",
				"select count(*) from webhook_deliveries where status = 'PENDING'");
	}

	private static void gauge(MeterRegistry registry, JdbcClient jdbc, String name, String sql) {
		Gauge.builder(name, () -> jdbc.sql(sql).query(Long.class).single()).register(registry);
	}

}
