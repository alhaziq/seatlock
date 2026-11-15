package dev.seatlock.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class SchemaTest {

	@Autowired
	JdbcClient jdbc;

	@Test
	void migrationCreatesBookingTables() {
		List<String> tables = jdbc.sql("""
				select table_name from information_schema.tables
				where table_schema = 'public'""").query(String.class).list();
		assertThat(tables).contains("events", "seats", "holds");
	}

	@Test
	void migrationCreatesPaymentTables() {
		List<String> tables = jdbc.sql("""
				select table_name from information_schema.tables
				where table_schema = 'public'""").query(String.class).list();
		assertThat(tables).contains("payments", "outbox", "ledger_entries");
	}

}
