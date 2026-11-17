package dev.seatlock.booking.auth;

public class UsernameTakenException extends RuntimeException {

	UsernameTakenException(String username) {
		super("Username " + username + " is already taken");
	}

}
