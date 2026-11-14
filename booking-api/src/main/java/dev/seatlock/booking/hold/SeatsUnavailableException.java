package dev.seatlock.booking.hold;

public class SeatsUnavailableException extends RuntimeException {

	public SeatsUnavailableException(String message) {
		super(message);
	}

}
