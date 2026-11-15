package dev.seatlock.booking.settlement;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementService {

	private final SettlementRepository settlements;

	private final LedgerRepository ledger;

	private final PaymentProvider provider;

	private final Clock clock;

	SettlementService(SettlementRepository settlements, LedgerRepository ledger, PaymentProvider provider,
			Clock clock) {
		this.settlements = settlements;
		this.ledger = ledger;
		this.provider = provider;
		this.clock = clock;
	}

	/**
	 * Charges a PENDING payment and records the outcome; does nothing for an unknown or settled payment. The row
	 * lock and status check stop a second settle; the payment id as the provider's idempotency key covers a crash
	 * between the charge and the commit. A {@link ProviderTimeoutException} rolls everything back.
	 */
	@Transactional
	public void settle(UUID paymentId) {
		var payment = settlements.lockPayment(paymentId).orElse(null);
		if (payment == null || !payment.status().equals("PENDING")) {
			return;
		}
		// shortcut: the payment row stays locked during the provider call; move to claim-then-finalize with a recovery sweep when provider latency matters.
		ChargeResult result = provider.charge(paymentId, payment.amountCents(), payment.currency());
		Instant now = clock.instant();
		switch (result) {
			case ChargeResult.Approved approved -> {
				settlements.markSucceeded(paymentId, now);
				ledger.postPayment(paymentId, payment.eventId(), payment.amountCents(), payment.currency(), now);
				settlements.confirmHold(payment.holdId());
			}
			case ChargeResult.Declined declined -> fail(payment, declined.reason(), now);
		}
	}

	/** Marks a still-PENDING payment FAILED and releases its hold; leaves a settled payment alone. */
	@Transactional
	public void failUnsettled(UUID paymentId, String reason) {
		var payment = settlements.lockPayment(paymentId).orElse(null);
		if (payment == null || !payment.status().equals("PENDING")) {
			return;
		}
		fail(payment, reason, clock.instant());
	}

	private void fail(SettlementRepository.LockedPayment payment, String reason, Instant now) {
		settlements.markFailed(payment.id(), reason, now);
		settlements.releaseHold(payment.holdId());
	}

}
