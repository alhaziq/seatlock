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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import dev.seatlock.booking.NotFoundException;
import dev.seatlock.booking.event.EventService;
import dev.seatlock.booking.webhook.WebhookEvents;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class HoldService {

	private final HoldRepository holds;

	private final EventService events;

	private final WebhookEvents webhooks;

	private final Clock clock;

	private final MeterRegistry meters;

	private final Duration holdDuration;

	HoldService(HoldRepository holds, EventService events, WebhookEvents webhooks, Clock clock, MeterRegistry meters,
			@Value("${seatlock.hold-duration}") Duration holdDuration) {
		this.holds = holds;
		this.events = events;
		this.webhooks = webhooks;
		this.clock = clock;
		this.meters = meters;
		this.holdDuration = holdDuration;
	}

	/** Lock, insert and update run in one transaction; any failure rolls all of it back. */
	@Transactional
	public Hold createHold(UUID eventId, UUID userId, List<UUID> seatIds) {
		try {
			Hold hold = insertHold(eventId, userId, seatIds);
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					meters.counter("seatlock.holds", "outcome", "created").increment();
				}
			});
			return hold;
		}
		catch (SeatsUnavailableException e) {
			meters.counter("seatlock.holds", "outcome", "unavailable").increment();
			throw e;
		}
	}

	private Hold insertHold(UUID eventId, UUID userId, List<UUID> seatIds) {
		if (new HashSet<>(seatIds).size() != seatIds.size()) {
			throw new DuplicateSeatsException();
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

	/** Someone else's hold is reported exactly like a missing one. */
	public Hold getHold(UUID id, UUID userId) {
		return holds.findHold(id).filter(h -> h.userId().equals(userId))
				.orElseThrow(() -> new NotFoundException("Hold " + id + " not found"));
	}

	/** Expires every ACTIVE hold past its expiry and frees its seats; returns how many holds expired. */
	@Transactional
	public int releaseExpired() {
		var expired = holds.expireHolds(clock.instant());
		expired.forEach(h -> webhooks.bookingExpired(h.holdId(), h.eventId(), h.userId(), h.seatIds()));
		return expired.size();
	}

}
