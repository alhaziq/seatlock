package dev.seatlock.booking.hold;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.seatlock.booking.NotFoundException;
import dev.seatlock.booking.event.EventService;

@Service
public class HoldService {

	private final HoldRepository holds;

	private final EventService events;

	private final Clock clock;

	private final Duration holdDuration;

	HoldService(HoldRepository holds, EventService events, Clock clock,
			@Value("${seatlock.hold-duration}") Duration holdDuration) {
		this.holds = holds;
		this.events = events;
		this.clock = clock;
		this.holdDuration = holdDuration;
	}

	/** Lock, insert and update run in one transaction; any failure rolls all of it back. */
	@Transactional
	public Hold createHold(UUID eventId, UUID userId, List<UUID> seatIds) {
		if (new HashSet<>(seatIds).size() != seatIds.size()) {
			throw new IllegalArgumentException("seatIds must not contain duplicates");
		}
		events.getEvent(eventId);
		List<UUID> locked = holds.lockAvailableSeats(eventId, seatIds);
		if (locked.size() != seatIds.size()) {
			throw new SeatsUnavailableException("One or more requested seats are not available");
		}
		UUID holdId = UUID.randomUUID();
		Instant now = clock.instant();
		holds.insertHold(holdId, eventId, userId, now.plus(holdDuration), now);
		if (holds.markSeatsHeld(holdId, seatIds) != seatIds.size()) {
			throw new SeatsUnavailableException("One or more requested seats are not available");
		}
		return getHold(holdId);
	}

	public Hold getHold(UUID id) {
		return holds.findHold(id).orElseThrow(() -> new NotFoundException("Hold " + id + " not found"));
	}

	/** Expires every ACTIVE hold past its expiry and frees its seats; returns how many holds expired. */
	public int releaseExpired() {
		return holds.expireHolds(clock.instant());
	}

}
