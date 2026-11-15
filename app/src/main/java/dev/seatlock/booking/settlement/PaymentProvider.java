package dev.seatlock.booking.settlement;

import java.util.UUID;

public interface PaymentProvider {

	/**
	 * Charges once per {@code paymentId}, which is the provider's idempotency key: a repeat returns the first result.
	 * @throws ProviderTimeoutException when the provider does not answer in time
	 */
	ChargeResult charge(UUID paymentId, long amountCents, String currency);

}
