package dev.seatlock.booking.webhook;

import java.time.Instant;

record WebhookAttempt(int attempt, Instant attemptedAt, Integer statusCode, String error, String responseBody,
		long durationMs) {
}
