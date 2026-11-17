package dev.seatlock.booking.auth;

import static dev.seatlock.booking.ProblemJson.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import dev.seatlock.booking.IntegrationTest;
import dev.seatlock.booking.TestTokens;
import dev.seatlock.booking.event.Event;
import dev.seatlock.booking.event.EventService;
import dev.seatlock.booking.hold.Hold;
import dev.seatlock.booking.hold.HoldService;
import dev.seatlock.booking.payment.Payment;
import dev.seatlock.booking.payment.PaymentService;

@IntegrationTest
class SecurityTest {

	private static final byte[] SECRET = IntegrationTest.JWT_SECRET.getBytes(StandardCharsets.UTF_8);

	private static final byte[] OTHER_SECRET = "another-secret-another-secret-32b".getBytes(StandardCharsets.UTF_8);

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Clock clock;

	@Autowired
	TestTokens tokens;

	@Autowired
	EventService events;

	@Autowired
	HoldService holds;

	@Autowired
	PaymentService payments;

	private RestClient client() {
		return RestClient.builder().baseUrl("http://localhost:" + port)
				.defaultStatusHandler(status -> true, (req, res) -> { })
				.build();
	}

	private RestClient.ResponseSpec createEvent(String authorization) {
		var req = client().post().uri("/events");
		if (authorization != null) {
			req = req.header("Authorization", authorization);
		}
		return req.body(Map.of("name", "Launch Night", "startsAt", "2031-01-01T20:00:00Z", "seatCount", 3,
				"priceCents", 2500, "currency", "MYR")).retrieve();
	}

	private static JWTClaimsSet claims(Instant iat, Instant exp) {
		return new JWTClaimsSet.Builder().issuer("seatlock").subject(UUID.randomUUID().toString())
				.issueTime(Date.from(iat)).expirationTime(Date.from(exp)).build();
	}

	/** Test-only encoder: an HS256 token with any secret and any timestamps. */
	private static String mint(byte[] secret, Instant iat, Instant exp) throws Exception {
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims(iat, exp));
		jwt.sign(new MACSigner(secret));
		return jwt.serialize();
	}

	private int eventCount() {
		return jdbc.sql("select count(*) from events").query(Integer.class).single();
	}

	private void assertRejected(String authorization) {
		assertThat(createEvent(authorization).toBodilessEntity().getHeaders().getFirst("WWW-Authenticate"))
				.startsWith("Bearer");
		assertProblem(createEvent(authorization), HttpStatus.UNAUTHORIZED);
		assertThat(eventCount()).isZero();
	}

	@Test
	void missingTokenIs401() {
		assertRejected(null);
	}

	@Test
	void garbageTokenIs401() {
		assertRejected("Bearer not.a.jwt");
	}

	@Test
	void tokenSignedWithAnotherSecretIs401() throws Exception {
		Instant now = clock.instant();
		assertRejected("Bearer " + mint(OTHER_SECRET, now, now.plus(Duration.ofHours(1))));
	}

	@Test
	void unsignedAlgNoneTokenIs401() {
		Instant now = clock.instant();
		assertRejected("Bearer " + new PlainJWT(claims(now, now.plus(Duration.ofHours(1)))).serialize());
	}

	@Test
	void expiredTokenIs401() throws Exception {
		Instant now = clock.instant();
		assertRejected("Bearer " + mint(SECRET, now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1))));
	}

	@Test
	void tokenFromTheTestEncoderWithTheRightSecretIsAccepted() throws Exception {
		Instant now = clock.instant();
		assertThat(createEvent("Bearer " + mint(SECRET, now, now.plus(Duration.ofHours(1)))).toBodilessEntity()
				.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void eventReadsAndHealthArePublic() {
		Event event = events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), 3, 2500, "MYR",
				UUID.randomUUID());

		assertThat(client().get().uri("/events/{id}", event.id()).retrieve().toBodilessEntity().getStatusCode())
				.isEqualTo(HttpStatus.OK);
		assertThat(client().get().uri("/actuator/health").retrieve().toBodilessEntity().getStatusCode())
				.isEqualTo(HttpStatus.OK);
	}

	@Test
	void holdsAndPaymentsAreVisibleOnlyToTheirOwner() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		Event event = events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), 3, 2500, "MYR",
				UUID.randomUUID());
		Hold hold = holds.createHold(event.id(), a, List.of(events.listSeats(event.id()).get(0).id()));
		Payment payment = payments.requestPayment(hold.id(), a, "key-1");

		assertProblem(client().get().uri("/holds/{id}", hold.id()).header("Authorization", tokens.bearer(b))
				.retrieve(), HttpStatus.NOT_FOUND);
		assertProblem(client().get().uri("/payments/{id}", payment.id()).header("Authorization", tokens.bearer(b))
				.retrieve(), HttpStatus.NOT_FOUND);
		assertThat(client().get().uri("/holds/{id}", hold.id()).header("Authorization", tokens.bearer(a)).retrieve()
				.toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(client().get().uri("/payments/{id}", payment.id()).header("Authorization", tokens.bearer(a))
				.retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "0123456789abcdef" })
	void startupFailsWithoutAUsableSecret(String secret) {
		new ApplicationContextRunner().withUserConfiguration(SecurityConfig.class)
				.withBean(Clock.class, Clock::systemUTC)
				.withPropertyValues("seatlock.auth.jwt-secret=" + secret)
				.run(context -> assertThat(context).getFailure().rootCause()
						.hasMessageContaining("seatlock.auth.jwt-secret"));
	}

}
