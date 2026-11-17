package dev.seatlock.booking;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One Postgres, Kafka and Redis container per JVM, independent of Spring context caching. It is never stopped by a
 * context; Testcontainers' Ryuk removes it when the JVM exits.
 */
class TestcontainersConfiguration implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

	private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

	private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

	static {
		POSTGRES.start();
		REDIS.start();
		KAFKA.start();
	}

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		// A test may point Redis elsewhere (e.g. a closed port) through its own properties.
		if (context.getEnvironment().getProperty("spring.data.redis.port") == null) {
			TestPropertyValues.of("spring.data.redis.host=" + REDIS.getHost(),
					"spring.data.redis.port=" + REDIS.getMappedPort(6379)).applyTo(context);
		}
		TestPropertyValues.of(
				"spring.datasource.url=" + POSTGRES.getJdbcUrl(),
				"spring.datasource.username=" + POSTGRES.getUsername(),
				"spring.datasource.password=" + POSTGRES.getPassword(),
				"spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers()).applyTo(context);
	}

}
