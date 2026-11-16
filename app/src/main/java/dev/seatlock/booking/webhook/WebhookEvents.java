package dev.seatlock.booking.webhook;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/** Queues webhook deliveries; each call must run inside the transaction that changes the hold. */
@Component
public class WebhookEvents {

	private final WebhookDeliveryRepository deliveries;

	private final Clock clock;

	WebhookEvents(WebhookDeliveryRepository deliveries, Clock clock) {
		this.deliveries = deliveries;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void bookingConfirmed(UUID holdId, UUID eventId, UUID userId, List<UUID> seatIds, UUID paymentId,
			long amountCents, String currency) {
		Map<String, Object> data = base(holdId, eventId, userId, seatIds);
		data.put("paymentId", paymentId);
		data.put("amountCents", amountCents);
		data.put("currency", currency);
		enqueue("booking.confirmed", holdId, eventId, data);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void bookingExpired(UUID holdId, UUID eventId, UUID userId, List<UUID> seatIds) {
		enqueue("booking.expired", holdId, eventId, base(holdId, eventId, userId, seatIds));
	}

	private static Map<String, Object> base(UUID holdId, UUID eventId, UUID userId, List<UUID> seatIds) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("holdId", holdId);
		data.put("eventId", eventId);
		data.put("userId", userId);
		data.put("seatIds", seatIds);
		return data;
	}

	private void enqueue(String type, UUID holdId, UUID eventId, Map<String, Object> data) {
		UUID id = UUID.randomUUID();
		var now = clock.instant();
		String payload = JsonMapper.shared()
				.writeValueAsString(Map.of("id", id, "type", type, "createdAt", now.toString(), "data", data));
		deliveries.enqueue(id, eventId, holdId, type, payload, now);
	}

}
