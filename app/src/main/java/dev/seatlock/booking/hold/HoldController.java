package dev.seatlock.booking.hold;

import java.time.Duration;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.seatlock.booking.auth.CurrentUser;
import dev.seatlock.booking.ratelimit.RateLimiter;

import jakarta.validation.Valid;

@RestController
class HoldController {

	private final HoldService holds;

	private final RateLimiter rateLimiter;

	HoldController(HoldService holds, RateLimiter rateLimiter) {
		this.holds = holds;
		this.rateLimiter = rateLimiter;
	}

	@PostMapping("/events/{id}/holds")
	@ResponseStatus(HttpStatus.CREATED)
	Hold create(@PathVariable UUID id, @CurrentUser UUID userId,
			@Valid @RequestBody CreateHoldRequest request) {
		rateLimiter.check("hold:" + userId, 10, Duration.ofSeconds(10));
		return holds.createHold(id, userId, request.seatIds());
	}

	@GetMapping("/holds/{id}")
	Hold get(@PathVariable UUID id, @CurrentUser UUID userId) {
		return holds.getHold(id, userId);
	}

}
