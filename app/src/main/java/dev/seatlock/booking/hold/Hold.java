package dev.seatlock.booking.hold;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Hold(UUID id, UUID eventId, UUID userId, HoldStatus status, List<UUID> seatIds, Instant expiresAt) {
}
