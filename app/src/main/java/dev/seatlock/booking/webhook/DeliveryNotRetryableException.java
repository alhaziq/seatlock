package dev.seatlock.booking.webhook;

public class DeliveryNotRetryableException extends RuntimeException {

	public DeliveryNotRetryableException(String message) {
		super(message);
	}

}
