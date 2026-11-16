package dev.seatlock.booking.webhook;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class WebhookHttpConfig {

	/** Never follows redirects; the dispatcher uses {@code exchange}, so any status reaches it instead of throwing. */
	@Bean
	RestClient webhookRestClient() {
		HttpClient http = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(Duration.ofSeconds(2))
				.build();
		var factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(Duration.ofSeconds(5));
		return RestClient.builder().requestFactory(factory).build();
	}

}
