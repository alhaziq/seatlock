package dev.seatlock.booking.webhook;

public class InvalidWebhookUrlException extends RuntimeException {

	public InvalidWebhookUrlException(String message) {
		super(message);
	}

}
