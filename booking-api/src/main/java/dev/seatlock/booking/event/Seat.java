package dev.seatlock.booking.event;

import java.util.UUID;

public record Seat(UUID id, String label, SeatStatus status) {
}
