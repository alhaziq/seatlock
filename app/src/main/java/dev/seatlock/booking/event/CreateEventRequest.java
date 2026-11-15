package dev.seatlock.booking.event;

import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateEventRequest(@NotBlank String name, @NotNull Instant startsAt,
		@Min(1) @Max(10000) int seatCount) {
}
