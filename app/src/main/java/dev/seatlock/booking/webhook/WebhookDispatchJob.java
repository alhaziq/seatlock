package dev.seatlock.booking.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "seatlock.webhooks.enabled", havingValue = "true", matchIfMissing = true)
class WebhookDispatchJob {

	private final WebhookDispatcher dispatcher;

	WebhookDispatchJob(WebhookDispatcher dispatcher) {
		this.dispatcher = dispatcher;
	}

	@Scheduled(fixedDelayString = "${seatlock.webhooks.interval:PT1S}")
	void run() {
		dispatcher.dispatchBatch();
	}

}
