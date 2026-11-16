package dev.seatlock.booking.webhook;

import java.time.Instant;
import java.util.UUID;

public record WebhookEndpoint(UUID id, UUID organizerId, String url, String secret, Instant createdAt,
		Instant updatedAt) {
}
