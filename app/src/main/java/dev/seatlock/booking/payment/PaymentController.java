package dev.seatlock.booking.payment;

import java.time.Duration;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.seatlock.booking.auth.CurrentUser;
import dev.seatlock.booking.ratelimit.RateLimiter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
class PaymentController {

	private final PaymentService payments;

	private final RateLimiter rateLimiter;

	PaymentController(PaymentService payments, RateLimiter rateLimiter) {
		this.payments = payments;
		this.rateLimiter = rateLimiter;
	}

	@PostMapping("/holds/{id}/pay")
	@ResponseStatus(HttpStatus.ACCEPTED)
	Payment pay(@PathVariable UUID id, @CurrentUser UUID userId,
			@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey) {
		rateLimiter.check("pay:" + userId, 10, Duration.ofSeconds(10));
		return payments.requestPayment(id, userId, idempotencyKey);
	}

	@GetMapping("/payments/{id}")
	Payment get(@PathVariable UUID id, @CurrentUser UUID userId) {
		return payments.getPayment(id, userId);
	}

}
