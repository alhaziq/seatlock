package dev.seatlock.booking;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One Postgres container per JVM, independent of Spring context caching. It is never stopped by a
 * context; Testcontainers' Ryuk removes it when the JVM exits.
 */
class TestcontainersConfiguration implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

	static {
		POSTGRES.start();
	}

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		TestPropertyValues.of(
				"spring.datasource.url=" + POSTGRES.getJdbcUrl(),
				"spring.datasource.username=" + POSTGRES.getUsername(),
				"spring.datasource.password=" + POSTGRES.getPassword()).applyTo(context);
	}

}
