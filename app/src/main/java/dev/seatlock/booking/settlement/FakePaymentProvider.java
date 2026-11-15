package dev.seatlock.booking.settlement;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Amounts ending in 01 are declined, 02 time out after the configured latency, all others are approved. */
@Component
public class FakePaymentProvider implements PaymentProvider {

	private final Duration latency;

	private final Map<UUID, ChargeResult> results = new ConcurrentHashMap<>();

	private final Map<UUID, Integer> charges = new ConcurrentHashMap<>();

	public FakePaymentProvider(@Value("${seatlock.provider.latency:PT0S}") Duration latency) {
		this.latency = latency;
	}

	@Override
	public ChargeResult charge(UUID paymentId, long amountCents, String currency) {
		if (amountCents % 100 == 2) {
			try {
				Thread.sleep(latency);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			throw new ProviderTimeoutException("Provider timed out charging payment " + paymentId);
		}
		// computeIfAbsent runs at most once per id, so concurrent repeats wait and get the remembered result.
		return results.computeIfAbsent(paymentId, id -> {
			if (amountCents % 100 == 1) {
				return new ChargeResult.Declined("INSUFFICIENT_FUNDS");
			}
			charges.merge(id, 1, Integer::sum);
			return new ChargeResult.Approved("fake_" + UUID.randomUUID());
		});
	}

	@Override
	public Optional<ChargeResult> findCharge(UUID paymentId) {
		return Optional.ofNullable(results.get(paymentId));
	}

	/** How many times money actually moved for this id: 0 or 1. */
	public int chargesMade(UUID paymentId) {
		return charges.getOrDefault(paymentId, 0);
	}

}
