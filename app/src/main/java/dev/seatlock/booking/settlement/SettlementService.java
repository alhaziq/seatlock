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
		finish(payment, provider.charge(paymentId, payment.amountCents(), payment.currency()));
	}

	/**
	 * Gives up on a still-PENDING payment, unless the provider already charged it: then the charge is posted as by
	 * {@link #settle}. Otherwise the payment is FAILED (with the provider's decline reason, if any) and its hold
	 * released. A settled or unknown payment is left alone.
	 */
	@Transactional
	public void failUnsettled(UUID paymentId, String reason) {
		var payment = settlements.lockPayment(paymentId).orElse(null);
		if (payment == null || !payment.status().equals("PENDING")) {
			return;
		}
		// shortcut: a real provider could still complete an in-flight charge after this lookup; a real integration would void by idempotency key before giving up.
		finish(payment, provider.findCharge(paymentId).orElse(new ChargeResult.Declined(reason)));
	}

	private void finish(SettlementRepository.LockedPayment payment, ChargeResult result) {
		Instant now = clock.instant();
		switch (result) {
			case ChargeResult.Approved approved -> {
				settlements.markSucceeded(payment.id(), now);
				ledger.postPayment(payment.id(), payment.eventId(), payment.amountCents(), payment.currency(), now);
				settlements.confirmHold(payment.holdId());
			}
			case ChargeResult.Declined declined -> {
				settlements.markFailed(payment.id(), declined.reason(), now);
				settlements.releaseHold(payment.holdId());
			}
		}
	}

}
