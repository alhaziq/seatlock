package dev.seatlock.booking.event;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.seatlock.booking.auth.CurrentUser;

import jakarta.validation.Valid;

@RestController
class EventController {

	private final EventService events;

	EventController(EventService events) {
		this.events = events;
	}

	@PostMapping("/events")
	@ResponseStatus(HttpStatus.CREATED)
	Event create(@CurrentUser UUID organizerId, @Valid @RequestBody CreateEventRequest request) {
		return events.createEvent(request.name(), request.startsAt(), request.seatCount(), request.priceCents(),
				request.currency(), organizerId);
	}

	@GetMapping("/events/{id}")
	Event get(@PathVariable UUID id) {
		return events.getEvent(id);
	}

	@GetMapping("/events/{id}/seats")
	List<Seat> seats(@PathVariable UUID id) {
		return events.listSeats(id);
	}

}
