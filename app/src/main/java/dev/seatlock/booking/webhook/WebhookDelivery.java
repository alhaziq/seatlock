package dev.seatlock.booking.webhook;

import java.time.Instant;
import java.util.UUID;

record WebhookDelivery(UUID id, String type, UUID holdId, String payload, DeliveryStatus status, int attempts,
		Instant nextAttemptAt, Instant createdAt, Instant deliveredAt) {
}
