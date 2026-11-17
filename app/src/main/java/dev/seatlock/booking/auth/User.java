package dev.seatlock.booking.auth;

import java.time.Instant;
import java.util.UUID;

record User(UUID id, String username, String passwordHash, Instant createdAt) {
}
