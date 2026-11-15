package dev.seatlock.booking.settlement;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
class SettlementKafkaConfig {

	private static final Logger log = LoggerFactory.getLogger(SettlementKafkaConfig.class);

	/**
	 * Retries a failed record {@code max-retries} times, {@code retry-backoff} apart, then recovers it: a payment the
	 * provider kept timing out on is given up with {@code PROVIDER_UNAVAILABLE}; anything else is logged and skipped.
	 * If recovery itself throws, the record is not acknowledged and is delivered again.
	 */
	@Bean
	DefaultErrorHandler settlementErrorHandler(SettlementService settlement,
			@Value("${seatlock.settlement.retry-backoff:PT1S}") Duration backoff,
			@Value("${seatlock.settlement.max-retries:3}") long maxRetries) {
		var handler = new DefaultErrorHandler((record, e) -> {
			if (e.getCause() instanceof ProviderTimeoutException) {
				settlement.failUnsettled(PaymentRequestedListener.paymentId((String) record.value()),
						"PROVIDER_UNAVAILABLE");
			}
			else {
				log.error("Giving up on {}-{}@{}", record.topic(), record.partition(), record.offset(), e);
			}
		}, new FixedBackOff(backoff.toMillis(), maxRetries));
		// Retrying a deadlock or unique violation would only hide a race.
		handler.addNotRetryableExceptions(PessimisticLockingFailureException.class, DuplicateKeyException.class);
		return handler;
	}

}
