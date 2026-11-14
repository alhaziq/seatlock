package dev.seatlock.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test clock starting at a whole-second instant so Postgres timestamptz round-trips it exactly. */
public class MutableClock extends Clock {

	public static final Instant BASE = Instant.parse("2030-01-01T00:00:00Z");

	private volatile Instant now = BASE;

	public void advance(Duration duration) {
		now = now.plus(duration);
	}

	public void reset() {
		now = BASE;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return now;
	}

}
