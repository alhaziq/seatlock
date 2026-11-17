package dev.seatlock.booking.hold;

import static dev.seatlock.booking.ProblemJson.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import dev.seatlock.booking.IntegrationTest;
import dev.seatlock.booking.TestTokens;
import dev.seatlock.booking.event.Event;
import dev.seatlock.booking.event.EventService;
import dev.seatlock.booking.event.Seat;
import dev.seatlock.booking.event.SeatStatus;

@IntegrationTest
class HoldApiTest {

	@LocalServerPort
	int port;

	@Autowired
	TestTokens tokens;

	@Autowired
	EventService events;

	@Autowired
	Clock clock;

	private RestClient client() {
		return RestClient.builder().baseUrl("http://localhost:" + port)
				.defaultStatusHandler(status -> true, (req, res) -> { })
				.build();
	}

	private Event event(int seatCount) {
		return events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), seatCount, 2500, "MYR", UUID.randomUUID());
	}

	private List<UUID> seatIds(Event event) {
		return events.listSeats(event.id()).stream().map(Seat::id).toList();
	}

	private List<SeatStatus> statuses(Event event) {
		return client().get().uri("/events/{id}/seats", event.id()).retrieve()
				.body(new ParameterizedTypeReference<List<Seat>>() { }).stream().map(Seat::status).toList();
	}

	private RestClient.ResponseSpec hold(UUID eventId, UUID userId, List<UUID> seatIds) {
		return client().post().uri("/events/{id}/holds", eventId)
				.header("Authorization", tokens.bearer(userId))
				.body(Map.of("seatIds", seatIds))
				.retrieve();
	}

	private HttpStatus status(RestClient.ResponseSpec spec) {
		return HttpStatus.valueOf(spec.toBodilessEntity().getStatusCode().value());
	}

	@Test
	void holdReturns201AndMarksSeatsHeld() {
		Event event = event(3);
		List<UUID> seats = seatIds(event);

		ResponseEntity<Hold> res = hold(event.id(), UUID.randomUUID(), seats.subList(0, 2)).toEntity(Hold.class);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(res.getBody().status()).isEqualTo(HoldStatus.ACTIVE);
		assertThat(res.getBody().expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
		assertThat(statuses(event)).containsExactly(SeatStatus.HELD, SeatStatus.HELD, SeatStatus.AVAILABLE);
		assertThat(client().get().uri("/events/{id}", event.id()).retrieve().body(Event.class).availableSeats())
				.isEqualTo(1);
	}

	@Test
	void holdingAHeldSeatIs409AndHoldsNothing() {
		Event event = event(3);
		List<UUID> seats = seatIds(event);
		assertThat(status(hold(event.id(), UUID.randomUUID(), List.of(seats.get(0))))).isEqualTo(HttpStatus.CREATED);

		assertProblem(hold(event.id(), UUID.randomUUID(), seats.subList(0, 2)), HttpStatus.CONFLICT);
		assertThat(statuses(event).get(1)).isEqualTo(SeatStatus.AVAILABLE);
	}

	@Test
	void duplicateSeatIdsAre400() {
		Event event = event(3);
		UUID s1 = seatIds(event).get(0);

		assertProblem(hold(event.id(), UUID.randomUUID(), List.of(s1, s1)), HttpStatus.BAD_REQUEST);
		assertThat(statuses(event).get(0)).isEqualTo(SeatStatus.AVAILABLE);
	}

	@Test
	void emptySeatIdsAre400() {
		Event event = event(3);
		assertProblem(hold(event.id(), UUID.randomUUID(), List.of()), HttpStatus.BAD_REQUEST);
	}

	@Test
	void moreThanSixSeatsIs400() {
		Event event = event(7);
		assertProblem(hold(event.id(), UUID.randomUUID(), seatIds(event)), HttpStatus.BAD_REQUEST);
		assertThat(statuses(event)).containsOnly(SeatStatus.AVAILABLE);
	}

	@Test
	void seatFromAnotherEventIs409() {
		Event first = event(3);
		Event second = event(3);
		List<UUID> wanted = List.of(seatIds(first).get(0), seatIds(second).get(0));

		assertProblem(hold(first.id(), UUID.randomUUID(), wanted), HttpStatus.CONFLICT);
		assertThat(statuses(first)).containsOnly(SeatStatus.AVAILABLE);
		assertThat(statuses(second)).containsOnly(SeatStatus.AVAILABLE);
	}

	@Test
	void unknownEventIs404() {
		assertProblem(hold(UUID.randomUUID(), UUID.randomUUID(), List.of(UUID.randomUUID())), HttpStatus.NOT_FOUND);
	}

	@Test
	void missingOrMalformedTokenIs401() {
		Event event = event(3);
		RestClient.ResponseSpec missing = client().post().uri("/events/{id}/holds", event.id())
				.body(Map.of("seatIds", List.of(seatIds(event).get(0)))).retrieve();
		RestClient.ResponseSpec malformed = client().post().uri("/events/{id}/holds", event.id())
				.header("Authorization", "Bearer not-a-jwt")
				.body(Map.of("seatIds", List.of(seatIds(event).get(0)))).retrieve();

		assertProblem(missing, HttpStatus.UNAUTHORIZED);
		assertProblem(malformed, HttpStatus.UNAUTHORIZED);
		assertThat(statuses(event)).containsOnly(SeatStatus.AVAILABLE);
	}

	@Test
	void getHoldReturnsItsSeats() {
		Event event = event(3);
		List<UUID> seats = seatIds(event);
		UUID userId = UUID.randomUUID();
		Hold created = hold(event.id(), userId, List.of(seats.get(2), seats.get(0))).body(Hold.class);

		ResponseEntity<Hold> res = client().get().uri("/holds/{id}", created.id())
				.header("Authorization", tokens.bearer(userId)).retrieve().toEntity(Hold.class);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(res.getBody().eventId()).isEqualTo(event.id());
		assertThat(res.getBody().userId()).isEqualTo(userId);
		assertThat(res.getBody().status()).isEqualTo(HoldStatus.ACTIVE);
		assertThat(res.getBody().seatIds()).containsExactlyInAnyOrder(seats.get(0), seats.get(2));
		assertThat(res.getBody().expiresAt()).isEqualTo(created.expiresAt());
	}

	@Test
	void unknownHoldIs404() {
		assertProblem(client().get().uri("/holds/{id}", UUID.randomUUID())
				.header("Authorization", tokens.bearer(UUID.randomUUID())).retrieve(), HttpStatus.NOT_FOUND);
	}

	@Test
	void unexpectedIllegalArgumentIsNot400() throws Exception {
		Class<?> handler = Class.forName("dev.seatlock.booking.ApiExceptionHandler");
		assertThat(java.util.Arrays.stream(handler.getDeclaredMethods())
				.map(m -> m.getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class))
				.filter(java.util.Objects::nonNull)
				.flatMap(a -> java.util.Arrays.stream(a.value())))
				.doesNotContain(IllegalArgumentException.class);
	}

}
