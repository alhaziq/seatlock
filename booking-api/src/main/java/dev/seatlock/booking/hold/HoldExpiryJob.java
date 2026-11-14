package dev.seatlock.booking.hold;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "seatlock.expiry.enabled", havingValue = "true", matchIfMissing = true)
class HoldExpiryJob {

	private final HoldService holds;

	HoldExpiryJob(HoldService holds) {
		this.holds = holds;
	}

	@Scheduled(fixedDelayString = "${seatlock.expiry.interval:PT5S}")
	void run() {
		holds.releaseExpired();
	}

}
