package dev.seatlock.booking.payment;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
class PaymentController {

	private final PaymentService payments;

	PaymentController(PaymentService payments) {
		this.payments = payments;
	}

	@PostMapping("/holds/{id}/pay")
	@ResponseStatus(HttpStatus.ACCEPTED)
	Payment pay(@PathVariable UUID id, @RequestHeader("X-User-Id") UUID userId,
			@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey) {
		return payments.requestPayment(id, userId, idempotencyKey);
	}

	@GetMapping("/payments/{id}")
	Payment get(@PathVariable UUID id) {
		return payments.getPayment(id);
	}

}
