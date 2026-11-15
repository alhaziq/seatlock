package dev.seatlock.booking.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.seatlock.booking.NotFoundException;
import dev.seatlock.booking.outbox.OutboxRepository;

@Service
public class PaymentService {

	static final String REQUESTED_TOPIC = "payments.requested";

	private static final String KEY_CONSTRAINT = "payments_user_id_idempotency_key_key";

	private final PaymentRepository payments;

	private final OutboxRepository outbox;

	private final Clock clock;

	PaymentService(PaymentRepository payments, OutboxRepository outbox, Clock clock) {
		this.payments = payments;
		this.outbox = outbox;
		this.clock = clock;
	}

	/**
	 * Claims the hold first, so a simultaneous request with the same key waits on the hold row and then
	 * finds this request's payment as a replay. Any exception rolls the claim back.
	 */
	@Transactional
	public Payment requestPayment(UUID holdId, UUID userId, String idempotencyKey) {
		Instant now = clock.instant();
		Optional<UUID> eventId = payments.claimHold(holdId, userId, now);
		Optional<Payment> earlier = payments.findByKey(userId, idempotencyKey);
		if (eventId.isEmpty()) {
			if (earlier.isPresent()) {
				if (earlier.get().holdId().equals(holdId)) {
					return earlier.get();
				}
				throw new IdempotencyKeyConflictException();
			}
			if (!payments.holdExists(holdId, userId)) {
				throw new NotFoundException("Hold " + holdId + " not found");
			}
			throw new HoldNotPayableException("Hold " + holdId + " is not active or has expired");
		}
		if (earlier.isPresent()) {
			throw new IdempotencyKeyConflictException();
		}
		PaymentRepository.Price price = payments.priceOf(holdId, eventId.get());
		Payment payment = new Payment(UUID.randomUUID(), holdId, PaymentStatus.PENDING, price.amountCents(),
				price.currency(), null);
		try {
			payments.insert(payment, userId, idempotencyKey, now);
		}
		catch (DuplicateKeyException e) {
			// Two different holds raced with one key and the other committed first.
			if (e.getMessage() != null && e.getMessage().contains(KEY_CONSTRAINT)) {
				throw new IdempotencyKeyConflictException();
			}
			throw e;
		}
		outbox.add(REQUESTED_TOPIC, payment.id().toString(), "{\"paymentId\":\"" + payment.id() + "\"}", now);
		return payment;
	}

	public Payment getPayment(UUID id) {
		return payments.findById(id).orElseThrow(() -> new NotFoundException("Payment " + id + " not found"));
	}

}
