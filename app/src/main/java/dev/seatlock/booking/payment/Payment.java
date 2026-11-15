package dev.seatlock.booking.payment;

import java.util.UUID;

public record Payment(UUID id, UUID holdId, PaymentStatus status, long amountCents, String currency,
		String failureReason) {
}
