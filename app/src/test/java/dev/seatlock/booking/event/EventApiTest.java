package dev.seatlock.booking.event;

import static dev.seatlock.booking.ProblemJson.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import dev.seatlock.booking.IntegrationTest;

@IntegrationTest
class EventApiTest {

	@LocalServerPort
	int port;

	private RestClient client() {
		return RestClient.builder().baseUrl("http://localhost:" + port)
				.defaultStatusHandler(status -> true, (req, res) -> { })
				.build();
	}

	private RestClient.ResponseSpec post(String name, int seatCount) {
		return post(name, seatCount, 2500, "MYR");
	}

	private RestClient.ResponseSpec post(String name, int seatCount, long priceCents, String currency) {
		return client().post().uri("/events")
				.body(Map.of("name", name, "startsAt", "2027-01-01T20:00:00Z", "seatCount", seatCount,
						"priceCents", priceCents, "currency", currency))
				.retrieve();
	}

	private ResponseEntity<Event> create(String name, int seatCount) {
		return post(name, seatCount).toEntity(Event.class);
	}

	@Test
	void createEventReturns201WithAllSeatsAvailable() {
		ResponseEntity<Event> res = create("Launch Night", 100);
		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(res.getBody().totalSeats()).isEqualTo(100);
		assertThat(res.getBody().availableSeats()).isEqualTo(100);
	}

	@Test
	void listSeatsReturnsLabelsOneToN() {
		Event event = create("Launch Night", 3).getBody();
		List<Seat> seats = client().get().uri("/events/{id}/seats", event.id()).retrieve()
				.body(new ParameterizedTypeReference<>() { });
		assertThat(seats).extracting(Seat::label).containsExactly("1", "2", "3");
		assertThat(seats).extracting(Seat::status).containsOnly(SeatStatus.AVAILABLE);
	}

	@Test
	void unknownEventIs404() {
		UUID id = UUID.randomUUID();
		assertProblem(client().get().uri("/events/{id}", id).retrieve(), HttpStatus.NOT_FOUND);
		assertProblem(client().get().uri("/events/{id}/seats", id).retrieve(), HttpStatus.NOT_FOUND);
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1, 10_001 })
	void invalidSeatCountIs400(int seatCount) {
		assertProblem(post("Launch Night", seatCount), HttpStatus.BAD_REQUEST);
	}

	@Test
	void blankNameIs400() {
		assertProblem(post(" ", 10), HttpStatus.BAD_REQUEST);
	}

	@Test
	void createEventReturnsPriceAndCurrency() {
		ResponseEntity<Event> res = post("Launch Night", 3, 2500, "MYR").toEntity(Event.class);
		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(res.getBody().priceCents()).isEqualTo(2500);
		assertThat(res.getBody().currency()).isEqualTo("MYR");
	}

	@ParameterizedTest
	@ValueSource(longs = { 0, -1 })
	void nonPositivePriceIs400(long priceCents) {
		assertProblem(post("Launch Night", 3, priceCents, "MYR"), HttpStatus.BAD_REQUEST);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "my", "myr", "MYRR" })
	void badCurrencyIs400(String currency) {
		assertProblem(post("Launch Night", 3, 2500, currency), HttpStatus.BAD_REQUEST);
	}

}
