package dev.seatlock.booking.payment;

public class HoldNotPayableException extends RuntimeException {

	public HoldNotPayableException(String message) {
		super(message);
	}

}
