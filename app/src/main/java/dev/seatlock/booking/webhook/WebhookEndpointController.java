package dev.seatlock.booking.webhook;

import java.time.Instant;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import dev.seatlock.booking.auth.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
class WebhookEndpointController {

	record PutRequest(@NotBlank @Size(max = 2000) String url) {
	}

	record PutResponse(String url, String secret, Instant createdAt, Instant updatedAt) {
	}

	record GetResponse(String url, Instant createdAt, Instant updatedAt) {
	}

	private final WebhookEndpointService service;

	WebhookEndpointController(WebhookEndpointService service) {
		this.service = service;
	}

	@PutMapping("/webhook-endpoint")
	PutResponse put(@CurrentUser UUID organizerId, @Valid @RequestBody PutRequest request) {
		WebhookEndpoint e = service.put(organizerId, request.url());
		return new PutResponse(e.url(), e.secret(), e.createdAt(), e.updatedAt());
	}

	@GetMapping("/webhook-endpoint")
	GetResponse get(@CurrentUser UUID organizerId) {
		WebhookEndpoint e = service.get(organizerId);
		return new GetResponse(e.url(), e.createdAt(), e.updatedAt());
	}

}
