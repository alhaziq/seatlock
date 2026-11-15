package dev.seatlock.booking.payment;

public class IdempotencyKeyConflictException extends RuntimeException {

	public IdempotencyKeyConflictException() {
		super("Idempotency-Key was already used for a different hold");
	}

}
