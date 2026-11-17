package dev.seatlock.booking;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import dev.seatlock.booking.auth.UsernameTakenException;
import dev.seatlock.booking.hold.DuplicateSeatsException;
import dev.seatlock.booking.hold.SeatsUnavailableException;
import dev.seatlock.booking.payment.HoldNotPayableException;
import dev.seatlock.booking.payment.IdempotencyKeyConflictException;
import dev.seatlock.booking.webhook.DeliveryNotRetryableException;
import dev.seatlock.booking.webhook.InvalidWebhookUrlException;

// Extends the base handler so bean-validation failures (MethodArgumentNotValidException) and missing or
// malformed headers (MissingRequestHeaderException, TypeMismatchException) become 400 ProblemDetail.
// SecurityConfig also routes Spring Security's 401/403 here.
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<ProblemDetail> unauthorized(AuthenticationException e) {
		return withBearerChallenge(HttpStatus.UNAUTHORIZED, e.getMessage());
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ProblemDetail> forbidden(AccessDeniedException e) {
		return withBearerChallenge(HttpStatus.FORBIDDEN, e.getMessage());
	}

	private static ResponseEntity<ProblemDetail> withBearerChallenge(HttpStatus status, String detail) {
		return ResponseEntity.status(status).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
				.body(ProblemDetail.forStatusAndDetail(status, detail));
	}

	@ExceptionHandler(UsernameTakenException.class)
	ProblemDetail usernameTaken(UsernameTakenException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

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

	@ExceptionHandler(InvalidWebhookUrlException.class)
	ProblemDetail invalidWebhookUrl(InvalidWebhookUrlException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler(DeliveryNotRetryableException.class)
	ProblemDetail deliveryNotRetryable(DeliveryNotRetryableException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

}
