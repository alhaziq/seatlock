package dev.seatlock.booking.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Builds the {@code Seatlock-Signature} header: hex HMAC-SHA256 of {@code "<t>.<body>"} keyed by the whole secret. */
public final class WebhookSigner {

	private WebhookSigner() {
	}

	public static String signatureHeader(String secret, long unixSeconds, String body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] digest = mac.doFinal((unixSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
			return "t=" + unixSeconds + ",v1=" + HexFormat.of().formatHex(digest);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 unavailable", e);
		}
	}

}
