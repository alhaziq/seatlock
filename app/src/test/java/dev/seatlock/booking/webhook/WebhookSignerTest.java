package dev.seatlock.booking.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class WebhookSignerTest {

	static String hmacHex(String secret, String message) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void matchesIndependentHmac() throws Exception {
		String header = WebhookSigner.signatureHeader("whsec_test", 1700000000L, "{\"a\":1}");

		assertThat(header).isEqualTo("t=1700000000,v1=" + hmacHex("whsec_test", "1700000000.{\"a\":1}"));
		assertThat(header.substring(header.indexOf("v1=") + 3)).matches("[0-9a-f]{64}");
	}

}
