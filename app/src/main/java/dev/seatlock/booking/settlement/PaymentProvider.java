package dev.seatlock.booking.settlement;

import java.util.Optional;
import java.util.UUID;

public interface PaymentProvider {

	/**
	 * Charges once per {@code paymentId}, which is the provider's idempotency key: a repeat returns the first result.
	 * @throws ProviderTimeoutException when the provider does not answer in time
	 */
	ChargeResult charge(UUID paymentId, long amountCents, String currency);

	/** The remembered result of an earlier charge for this id, or empty if there was none; never moves money. */
	Optional<ChargeResult> findCharge(UUID paymentId);

}
