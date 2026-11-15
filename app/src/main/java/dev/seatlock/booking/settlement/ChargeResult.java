package dev.seatlock.booking.settlement;

public sealed interface ChargeResult permits ChargeResult.Approved, ChargeResult.Declined {

	record Approved(String providerReference) implements ChargeResult {
	}

	record Declined(String reason) implements ChargeResult {
	}

}
