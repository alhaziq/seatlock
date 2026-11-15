package dev.seatlock.booking.settlement;

import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import dev.seatlock.booking.outbox.KafkaTopics;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "seatlock.settlement.enabled", havingValue = "true", matchIfMissing = true)
class PaymentRequestedListener {

	private static final Logger log = LoggerFactory.getLogger(PaymentRequestedListener.class);

	private final SettlementService settlement;

	PaymentRequestedListener(SettlementService settlement) {
		this.settlement = settlement;
	}

	/**
	 * Settles the payment named in the message. A message that cannot be parsed is logged and skipped; an unknown
	 * or already settled payment is a no-op in {@link SettlementService#settle}, so duplicates are harmless. Other
	 * exceptions go to the error handler in {@link SettlementKafkaConfig}.
	 */
	@KafkaListener(topics = KafkaTopics.PAYMENTS_REQUESTED, groupId = "seatlock-worker")
	void onPaymentRequested(ConsumerRecord<String, String> record) {
		UUID paymentId;
		try {
			paymentId = paymentId(record.value());
		}
		catch (IllegalArgumentException e) {
			log.warn("Skipping unreadable message at {}-{}@{}: {}", record.topic(), record.partition(),
					record.offset(), e.getMessage());
			return;
		}
		settlement.settle(paymentId);
	}

	/** Reads {@code {"paymentId":"<uuid>"}}; throws {@link IllegalArgumentException} for anything else. */
	static UUID paymentId(String json) {
		if (json == null) {
			throw new IllegalArgumentException("no payload");
		}
		try {
			// The JSON literal null reads as a null request.
			PaymentRequested request = JsonMapper.shared().readValue(json, PaymentRequested.class);
			if (request == null || request.paymentId() == null) {
				throw new IllegalArgumentException("no paymentId");
			}
			return request.paymentId();
		}
		catch (JacksonException e) {
			throw new IllegalArgumentException("not a payment request", e);
		}
	}

	private record PaymentRequested(UUID paymentId) {
	}

}
