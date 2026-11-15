package dev.seatlock.booking.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "seatlock.outbox.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelayJob {

	private final OutboxRelay relay;

	OutboxRelayJob(OutboxRelay relay) {
		this.relay = relay;
	}

	@Scheduled(fixedDelayString = "${seatlock.outbox.interval:PT0.5S}")
	void run() {
		relay.publishBatch();
	}

}
