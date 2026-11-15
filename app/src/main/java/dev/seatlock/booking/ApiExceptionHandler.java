package dev.seatlock.booking;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import dev.seatlock.booking.hold.DuplicateSeatsException;
import dev.seatlock.booking.hold.SeatsUnavailableException;
import dev.seatlock.booking.payment.HoldNotPayableException;
import dev.seatlock.booking.payment.IdempotencyKeyConflictException;

// Extends the base handler so bean-validation failures (MethodArgumentNotValidException) and a missing or
// malformed X-User-Id header (MissingRequestHeaderException, TypeMismatchException) become 400 ProblemDetail.
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(NotFoundException.class)
	ProblemDetail notFound(NotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler(SeatsUnavailableException.class)
	ProblemDetail seatsUnavailable(SeatsUnavailableException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	@ExceptionHandler(DuplicateSeatsException.class)
	ProblemDetail duplicateSeats(DuplicateSeatsException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler(HoldNotPayableException.class)
	ProblemDetail holdNotPayable(HoldNotPayableException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	@ExceptionHandler(IdempotencyKeyConflictException.class)
	ProblemDetail idempotencyKeyConflict(IdempotencyKeyConflictException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
	}

}
