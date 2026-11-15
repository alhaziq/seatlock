package dev.seatlock.booking.settlement;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
class SettlementKafkaConfig {

	/**
	 * A provider timeout is retried {@code max-retries} times, {@code retry-backoff} apart, then the payment is given
	 * up with {@code PROVIDER_UNAVAILABLE}. Any other failure is retried until it clears, because skipping a real
	 * payment would strand its seats. If recovery throws, the record is not acknowledged and is delivered again.
	 */
	@Bean
	DefaultErrorHandler settlementErrorHandler(SettlementService settlement,
			@Value("${seatlock.settlement.retry-backoff:PT1S}") Duration backoff,
			@Value("${seatlock.settlement.max-retries:3}") long maxRetries) {
		var handler = new DefaultErrorHandler((record, e) -> {
			if (!isProviderTimeout(e)) {
				throw new IllegalStateException("Only a provider timeout may be given up on", e);
			}
			settlement.failUnsettled(PaymentRequestedListener.paymentId((String) record.value()),
					"PROVIDER_UNAVAILABLE");
		}, new FixedBackOff(backoff.toMillis(), maxRetries));
		// shortcut: an unexpected failure blocks its partition until it clears; add a dead-letter topic plus a sweep that re-enqueues old PENDING payments when one stuck record must not hold up others.
		handler.setBackOffFunction((record, e) -> isProviderTimeout(e) ? null
				: new FixedBackOff(backoff.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS));
		return handler;
	}

	// The recoverer gets the listener's wrapper exception, the back-off function its root cause.
	private static boolean isProviderTimeout(Exception e) {
		return NestedExceptionUtils.getMostSpecificCause(e) instanceof ProviderTimeoutException;
	}

}
