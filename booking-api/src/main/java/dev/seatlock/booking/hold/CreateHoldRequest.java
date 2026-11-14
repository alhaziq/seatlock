package dev.seatlock.booking.hold;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateHoldRequest(@NotEmpty @Size(max = 6) List<@NotNull UUID> seatIds) {
}
