package dev.seatlock.booking.hold;

public class DuplicateSeatsException extends RuntimeException {

	public DuplicateSeatsException() {
		super("seatIds must not contain duplicates");
	}

}
