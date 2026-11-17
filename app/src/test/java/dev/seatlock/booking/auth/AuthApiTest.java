package dev.seatlock.booking.auth;

import static dev.seatlock.booking.ProblemJson.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.client.RestClient;

import dev.seatlock.booking.IntegrationTest;

@IntegrationTest
class AuthApiTest {

	private static final String PASSWORD = "correct horse";

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JwtDecoder decoder;

	private RestClient client() {
		return RestClient.builder().baseUrl("http://localhost:" + port)
				.defaultStatusHandler(status -> true, (req, res) -> { })
				.build();
	}

	private RestClient.ResponseSpec signup(String username, String password) {
		return client().post().uri("/auth/signup").body(Map.of("username", username, "password", password)).retrieve();
	}

	private RestClient.ResponseSpec login(String username, String password) {
		return client().post().uri("/auth/login").body(Map.of("username", username, "password", password)).retrieve();
	}

	@Test
	void signupReturns201WithoutThePasswordAndStoresABcryptHash() {
		ResponseEntity<String> res = signup("alice_1", PASSWORD).toEntity(String.class);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(res.getBody()).contains("\"username\":\"alice_1\"").contains("\"id\"").doesNotContain(PASSWORD)
				.doesNotContain("password");
		assertThat(jdbc.sql("select password_hash from users where username = 'alice_1'").query(String.class).single())
				.startsWith("$2");
	}

	@Test
	void duplicateUsernameIs409() {
		assertThat(signup("alice", PASSWORD).toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertProblem(signup("alice", "another password"), HttpStatus.CONFLICT);
	}

	@ParameterizedTest
	@CsvSource({ "Ab, longenough", "'has space', longenough", "alice, 1234567",
			"alice, 1234567890123456789012345678901234567890123456789012345678901234567890123" })
	void invalidUsernameOrPasswordIs400(String username, String password) {
		assertProblem(signup(username, password), HttpStatus.BAD_REQUEST);
		assertThat(jdbc.sql("select count(*) from users").query(Integer.class).single()).isZero();
	}

	@Test
	void loginReturnsABearerTokenForTheSignedUpUser() {
		Map<?, ?> user = signup("alice", PASSWORD).body(Map.class);

		Map<?, ?> body = login("alice", PASSWORD).body(Map.class);

		assertThat(body.get("tokenType")).isEqualTo("Bearer");
		assertThat(body.get("expiresIn")).isEqualTo(3600);
		Jwt jwt = decoder.decode((String) body.get("accessToken"));
		assertThat(jwt.getSubject()).isEqualTo(user.get("id"));
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("seatlock");
		assertThat(jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond()).isEqualTo(3600);
	}

	@Test
	void wrongPasswordAndUnknownUserAre401WithTheSameDetail() {
		signup("alice", PASSWORD).toBodilessEntity();

		assertProblem(login("alice", "wrong password"), HttpStatus.UNAUTHORIZED);
		assertProblem(login("nobody", PASSWORD), HttpStatus.UNAUTHORIZED);
		Object wrongPassword = login("alice", "wrong password").body(Map.class).get("detail");
		Object unknownUser = login("nobody", PASSWORD).body(Map.class).get("detail");
		assertThat(wrongPassword).isNotNull().isEqualTo(unknownUser);
	}

}
