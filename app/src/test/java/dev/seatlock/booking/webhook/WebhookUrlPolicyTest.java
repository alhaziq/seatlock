package dev.seatlock.booking.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookUrlPolicyTest {

	private final WebhookUrlPolicy strict = new WebhookUrlPolicy(false, false);

	@ParameterizedTest
	@ValueSource(strings = { "http://example.com", "ftp://example.com", "https://user:pw@example.com",
			"https://127.0.0.1/x", "https://localhost/x", "https://169.254.169.254/", "https://10.0.0.5/",
			"https://192.168.1.1/", "https://[::1]/", "https://[fd00::1]/", "https://100.64.0.1/", "https://100.100.100.200/",
			"https://0.1.2.3/", "not a url" })
	void rejectsUnsafeUrls(String url) {
		assertThatThrownBy(() -> strict.check(url)).isInstanceOf(InvalidWebhookUrlException.class);
	}

	@Test
	void acceptsPublicLiteralIp() {
		assertThat(strict.check("https://93.184.215.14/hook").getHost()).isEqualTo("93.184.215.14");
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://100.63.255.255/", "https://100.128.0.1/" })
	void acceptsPublicAddressesNextToCarrierGradeNat(String url) {
		assertThat(strict.check(url)).isNotNull();
	}

	@Test
	void permissiveFlagsAcceptLocalhostHttp() {
		assertThat(new WebhookUrlPolicy(true, true).check("http://localhost:8080/hook").getPort()).isEqualTo(8080);
	}

}
