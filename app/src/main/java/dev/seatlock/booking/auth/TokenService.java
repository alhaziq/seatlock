package dev.seatlock.booking.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

	static final String ISSUER = "seatlock";

	private final JwtEncoder encoder;

	private final Clock clock;

	private final Duration ttl;

	TokenService(JwtEncoder encoder, Clock clock, @Value("${seatlock.auth.token-ttl}") Duration ttl) {
		this.encoder = encoder;
		this.clock = clock;
		this.ttl = ttl;
	}

	/** An HS256 access token whose {@code sub} is the user id, valid for {@code seatlock.auth.token-ttl}. */
	public String issue(UUID userId) {
		Instant now = clock.instant();
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer(ISSUER).subject(userId.toString()).issuedAt(now)
				.expiresAt(now.plus(ttl)).build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

	Duration ttl() {
		return ttl;
	}

}
