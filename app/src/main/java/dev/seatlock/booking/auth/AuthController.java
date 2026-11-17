package dev.seatlock.booking.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.seatlock.booking.ratelimit.RateLimiter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
class AuthController {

	record Credentials(@NotNull @Pattern(regexp = "^[a-z0-9_]{3,32}$") String username,
			@NotNull @Size(min = 8, max = 72) String password) {

		/** BCrypt hashes only the first 72 bytes and rejects longer input; 72 non-ASCII chars can exceed that. */
		@AssertTrue(message = "password must be at most 72 bytes")
		boolean isPasswordWithinBcryptLimit() {
			return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
		}

	}

	record SignupResponse(UUID id, String username) {
	}

	record TokenResponse(String accessToken, String tokenType, long expiresIn) {
	}

	/** Verified against when the user is missing, so unknown usernames cost the same BCrypt time as wrong passwords. */
	private final String dummyHash;

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final TokenService tokens;

	private final Clock clock;

	private final RateLimiter rateLimiter;

	AuthController(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens, Clock clock,
			RateLimiter rateLimiter) {
		this.rateLimiter = rateLimiter;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.dummyHash = passwordEncoder.encode("dummy-password");
		this.tokens = tokens;
		this.clock = clock;
	}

	@PostMapping("/auth/signup")
	@ResponseStatus(HttpStatus.CREATED)
	SignupResponse signup(@Valid @RequestBody Credentials request) {
		User user = new User(UUID.randomUUID(), request.username(), passwordEncoder.encode(request.password()),
				clock.instant());
		users.insert(user);
		return new SignupResponse(user.id(), user.username());
	}

	/**
	 * Unknown user and wrong password fail with the same response and the same BCrypt cost, so login does not reveal
	 * which usernames exist by detail or timing. Signup's 409 does, by design.
	 */
	@PostMapping("/auth/login")
	TokenResponse login(@Valid @RequestBody Credentials request) {
		rateLimiter.check("login:" + request.username(), 10, Duration.ofSeconds(60));
		User user = users.findByUsername(request.username()).orElse(null);
		boolean ok = passwordEncoder.matches(request.password(), user == null ? dummyHash : user.passwordHash());
		if (user == null || !ok) {
			throw new BadCredentialsException("Invalid username or password");
		}
		return new TokenResponse(tokens.issue(user.id()), "Bearer", tokens.ttl().toSeconds());
	}

}
