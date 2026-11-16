package dev.seatlock.booking.webhook;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;

import org.springframework.stereotype.Service;

import dev.seatlock.booking.NotFoundException;

@Service
class WebhookEndpointService {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final WebhookEndpointRepository endpoints;

	private final WebhookUrlPolicy policy;

	private final Clock clock;

	WebhookEndpointService(WebhookEndpointRepository endpoints, WebhookUrlPolicy policy, Clock clock) {
		this.endpoints = endpoints;
		this.policy = policy;
		this.clock = clock;
	}

	/** Every call issues a new secret; that is how a secret is rotated. */
	WebhookEndpoint put(UUID organizerId, String url) {
		policy.check(url);
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		return endpoints.upsert(organizerId, url, secret, clock.instant());
	}

	WebhookEndpoint get(UUID organizerId) {
		return endpoints.findByOrganizer(organizerId)
				.orElseThrow(() -> new NotFoundException("no webhook endpoint registered"));
	}

}
