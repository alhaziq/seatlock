package dev.seatlock.booking;

import java.util.UUID;

import dev.seatlock.booking.auth.TokenService;

/** Mints {@code Authorization} header values with the app's own TokenService; the ids need no users row. */
public class TestTokens {

	private final TokenService tokens;

	TestTokens(TokenService tokens) {
		this.tokens = tokens;
	}

	public String bearer(UUID userId) {
		return "Bearer " + tokens.issue(userId);
	}

}
