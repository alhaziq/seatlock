package dev.seatlock.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@IntegrationTest
class ApiDocsTest {

	@LocalServerPort
	int port;

	@Test
	@SuppressWarnings("unchecked")
	void openApiListsTheBookingEndpoints() {
		ResponseEntity<Map> res = RestClient.builder().baseUrl("http://localhost:" + port)
				.defaultStatusHandler(status -> true, (req, r) -> { })
				.build().get().uri("/v3/api-docs").retrieve().toEntity(Map.class);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat((Map<String, Object>) res.getBody().get("paths")).containsKeys("/events",
				"/events/{id}/seats", "/events/{id}/holds", "/holds/{id}");
	}

}
