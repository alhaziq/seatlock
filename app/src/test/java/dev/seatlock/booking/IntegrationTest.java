package dev.seatlock.booking;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AliasFor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Boots the app on a random port against the shared Testcontainers Postgres, with a
 * {@link MutableClock} as the primary Clock. Every test starts and ends with empty tables and a reset clock.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({ IntegrationTest.TestClockConfig.class, TestTokens.class })
@TestPropertySource(properties = "seatlock.auth.jwt-secret=" + IntegrationTest.JWT_SECRET)
@ContextConfiguration(initializers = TestcontainersConfiguration.class)
@ExtendWith(IntegrationTest.CleanState.class)
public @interface IntegrationTest {

	/** Fixed HS256 secret (32+ bytes); set outside {@link #properties()} so {@code properties = {}} keeps it. */
	String JWT_SECRET = "test-secret-test-secret-test-secret!";

	/** Background jobs and the listener are off unless a test replaces these; {@code {}} lets profile files decide. */
	@AliasFor(annotation = SpringBootTest.class, attribute = "properties")
	String[] properties() default { "management.server.port=0", "seatlock.expiry.enabled=false", "seatlock.outbox.enabled=false",
			"seatlock.settlement.enabled=false", "seatlock.webhooks.enabled=false",
			"seatlock.webhooks.allow-http=true", "seatlock.webhooks.allow-private-addresses=true",
			"seatlock.rate-limit.enabled=false", "seatlock.seat-cache.enabled=false" };

	@TestConfiguration(proxyBeanMethods = false)
	class TestClockConfig {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock();
		}

	}

	class CleanState implements BeforeEachCallback, AfterEachCallback {

		@Override
		public void beforeEach(ExtensionContext context) {
			var ctx = SpringExtension.getApplicationContext(context);
			truncate(ctx);
			ctx.getBean(MutableClock.class).reset();
		}

		@Override
		public void afterEach(ExtensionContext context) {
			truncate(SpringExtension.getApplicationContext(context));
		}

		private static void truncate(ApplicationContext ctx) {
			ctx.getBean(JdbcClient.class).sql("truncate users, events, seats, holds, payments, outbox, ledger_entries, webhook_endpoints, webhook_deliveries, webhook_attempts cascade").update();
		}

	}

}
