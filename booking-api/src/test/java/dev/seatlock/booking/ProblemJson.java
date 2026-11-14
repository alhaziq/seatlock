package dev.seatlock.booking;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** Asserts an error response has the expected status and is a Spring ProblemDetail (problem+json). */
public final class ProblemJson {

	private ProblemJson() {
	}

	public static void assertProblem(RestClient.ResponseSpec spec, HttpStatus expected) {
		ResponseEntity<Void> res = spec.toBodilessEntity();
		assertThat(res.getStatusCode().value()).isEqualTo(expected.value());
		assertThat(res.getHeaders().getContentType()).isNotNull()
				.matches(MediaType.APPLICATION_PROBLEM_JSON::isCompatibleWith);
	}

}
