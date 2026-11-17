package dev.seatlock.booking.webhook;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.seatlock.booking.NotFoundException;
import dev.seatlock.booking.auth.CurrentUser;

@RestController
class WebhookDeliveryController {

	private static final int LIST_LIMIT = 50;

	record Summary(UUID id, String type, UUID holdId, DeliveryStatus status, int attempts, Instant nextAttemptAt,
			Instant createdAt, Instant deliveredAt) {

		static Summary of(WebhookDelivery d) {
			return new Summary(d.id(), d.type(), d.holdId(), d.status(), d.attempts(), d.nextAttemptAt(),
					d.createdAt(), d.deliveredAt());
		}

	}

	record Detail(UUID id, String type, UUID holdId, DeliveryStatus status, int attempts, Instant nextAttemptAt,
			Instant createdAt, Instant deliveredAt, String payload, List<WebhookAttempt> attemptLog) {

		static Detail of(WebhookDelivery d, List<WebhookAttempt> attemptLog) {
			return new Detail(d.id(), d.type(), d.holdId(), d.status(), d.attempts(), d.nextAttemptAt(),
					d.createdAt(), d.deliveredAt(), d.payload(), attemptLog);
		}

	}

	private final WebhookDeliveryRepository deliveries;

	private final Clock clock;

	WebhookDeliveryController(WebhookDeliveryRepository deliveries, Clock clock) {
		this.deliveries = deliveries;
		this.clock = clock;
	}

	@GetMapping("/webhook-deliveries")
	List<Summary> list(@CurrentUser UUID organizerId,
			@RequestParam(required = false) DeliveryStatus status) {
		return deliveries.list(organizerId, status, LIST_LIMIT).stream().map(Summary::of).toList();
	}

	@GetMapping("/webhook-deliveries/{id}")
	Detail get(@CurrentUser UUID organizerId, @PathVariable UUID id) {
		return Detail.of(find(organizerId, id), deliveries.attempts(id));
	}

	@PostMapping("/webhook-deliveries/{id}/retry")
	Detail retry(@CurrentUser UUID organizerId, @PathVariable UUID id) {
		WebhookDelivery retried = deliveries.retryDead(organizerId, id, clock.instant()).orElse(null);
		if (retried == null) {
			find(organizerId, id); // 404 when missing or not ours
			throw new DeliveryNotRetryableException("only a DEAD delivery can be retried");
		}
		return Detail.of(retried, deliveries.attempts(id));
	}

	private WebhookDelivery find(UUID organizerId, UUID id) {
		return deliveries.find(organizerId, id).orElseThrow(() -> new NotFoundException("webhook delivery not found"));
	}

}
