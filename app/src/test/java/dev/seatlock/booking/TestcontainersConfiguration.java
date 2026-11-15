package dev.seatlock.booking;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One Postgres and one Kafka container per JVM, independent of Spring context caching. It is never stopped by a
 * context; Testcontainers' Ryuk removes it when the JVM exits.
 */
class TestcontainersConfiguration implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

	private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

	static {
		POSTGRES.start();
		KAFKA.start();
	}

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		TestPropertyValues.of(
				"spring.datasource.url=" + POSTGRES.getJdbcUrl(),
				"spring.datasource.username=" + POSTGRES.getUsername(),
				"spring.datasource.password=" + POSTGRES.getPassword(),
				"spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers()).applyTo(context);
	}

}
