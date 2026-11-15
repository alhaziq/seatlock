package dev.seatlock.booking;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AliasFor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Boots the app on a random port against the shared Testcontainers Postgres, with a
 * {@link MutableClock} as the primary Clock. Every test starts with empty tables and a reset clock.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IntegrationTest.TestClockConfig.class)
@ContextConfiguration(initializers = TestcontainersConfiguration.class)
@ExtendWith(IntegrationTest.CleanState.class)
public @interface IntegrationTest {

	/** Background jobs and the listener are off unless a test replaces these; {@code {}} lets profile files decide. */
	@AliasFor(annotation = SpringBootTest.class, attribute = "properties")
	String[] properties() default { "seatlock.expiry.enabled=false", "seatlock.outbox.enabled=false",
			"seatlock.settlement.enabled=false" };

	@TestConfiguration(proxyBeanMethods = false)
	class TestClockConfig {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock();
		}

	}

	class CleanState implements BeforeEachCallback {

		@Override
		public void beforeEach(ExtensionContext context) {
			var ctx = SpringExtension.getApplicationContext(context);
			ctx.getBean(JdbcClient.class).sql("truncate events, seats, holds, payments, outbox, ledger_entries cascade").update();
			ctx.getBean(MutableClock.class).reset();
		}

	}

}
