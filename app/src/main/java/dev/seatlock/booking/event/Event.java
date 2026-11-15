package dev.seatlock.booking.event;

import java.time.Instant;
import java.util.UUID;

public record Event(UUID id, String name, Instant startsAt, long priceCents, String currency, int totalSeats, int availableSeats) {
}
