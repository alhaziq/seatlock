package dev.seatlock.booking.webhook;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import dev.seatlock.booking.webhook.WebhookDeliveryRepository.Claim;
import dev.seatlock.booking.webhook.WebhookDeliveryRepository.Result;

/**
 * Sends due webhook deliveries. Claim and record are separate short transactions; the HTTP call runs between
 * them outside any transaction. Safe to call from several threads or replicas at once.
 */
@Service
public class WebhookDispatcher {

	private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

	private static final int MAX_RESPONSE_CHARS = 1000;

	private final WebhookDeliveryRepository deliveries;

	private final WebhookUrlPolicy urlPolicy;

	private final RestClient http;

	private final TransactionTemplate tx;

	private final Clock clock;

	private final int batchSize;

	private final Duration lease;

	private final int maxAttempts;

	private final Duration baseBackoff;

	WebhookDispatcher(WebhookDeliveryRepository deliveries, WebhookUrlPolicy urlPolicy, RestClient webhookRestClient,
			TransactionTemplate tx, Clock clock, @Value("${seatlock.webhooks.batch-size}") int batchSize,
			@Value("${seatlock.webhooks.lease}") Duration lease,
			@Value("${seatlock.webhooks.max-attempts}") int maxAttempts,
			@Value("${seatlock.webhooks.base-backoff}") Duration baseBackoff) {
		this.deliveries = deliveries;
		this.urlPolicy = urlPolicy;
		this.http = webhookRestClient;
		this.tx = tx;
		this.clock = clock;
		this.batchSize = batchSize;
		this.lease = lease;
		this.maxAttempts = maxAttempts;
		this.baseBackoff = baseBackoff;
	}

	/** Claims, sends and records up to batch-size due deliveries; returns how many were attempted. */
	public int dispatchBatch() {
		int attempted = 0;
		// shortcut: sends within a batch are sequential, so one slow receiver delays the rest of its batch, move to per-endpoint workers when that matters.
		for (Claim claim : claim()) {
			try {
				if (deliver(claim)) {
					attempted++;
				}
			}
			catch (RuntimeException e) {
				// one bad row must not stop the batch; it is due again when its lease ends
				log.warn("Webhook delivery {} failed to dispatch", claim.id(), e);
			}
		}
		return attempted;
	}

	/** Sends and records one claimed delivery; returns false, touching nothing, when its lease has already run out. */
	boolean deliver(Claim claim) {
		if (!clock.instant().isBefore(claim.leaseUntil())) {
			return false; // the row is due again, so it belongs to whoever reclaims it
		}
		Result result = send(claim);
		Instant now = clock.instant();
		Instant retryAt = claim.attempts() >= maxAttempts ? null
				: now.plus(baseBackoff.multipliedBy(1L << (claim.attempts() - 1)));
		tx.executeWithoutResult(s -> deliveries.record(claim, result, retryAt));
		return true;
	}

	List<Claim> claim() {
		Instant now = clock.instant();
		return tx.execute(s -> deliveries.claim(now, now.plus(lease), batchSize));
	}

	private Result send(Claim claim) {
		Instant attemptedAt = clock.instant();
		long start = System.nanoTime();
		try {
			// shortcut: the JDK client resolves the host again after this check, so DNS rebinding can slip past it, pin the resolved address when that matters.
			URI uri = urlPolicy.check(claim.url());
			return http.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
					.header("Seatlock-Delivery-Id", claim.id().toString())
					.header("Seatlock-Event-Type", claim.type())
					.header("Seatlock-Signature",
							WebhookSigner.signatureHeader(claim.secret(), clock.instant().getEpochSecond(),
									claim.payload()))
					.body(claim.payload().getBytes(StandardCharsets.UTF_8))
					.exchange((request, response) -> new Result(response.getStatusCode().value(), null,
							head(response.getBody()), attemptedAt, millisSince(start)));
		}
		catch (InvalidWebhookUrlException e) {
			return new Result(null, "blocked by url policy: " + e.getMessage(), null, attemptedAt, millisSince(start));
		}
		catch (RuntimeException e) {
			String error = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
			return new Result(null, error, null, attemptedAt, millisSince(start));
		}
	}

	private static long millisSince(long startNanos) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
	}

	/** Reads at most {@link #MAX_RESPONSE_CHARS} characters of the response body. */
	private static String head(InputStream in) throws IOException {
		char[] buf = new char[MAX_RESPONSE_CHARS];
		int n = 0;
		try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			for (int r; n < buf.length && (r = reader.read(buf, n, buf.length - n)) > 0;) {
				n += r;
			}
		}
		return new String(buf, 0, n);
	}

}
