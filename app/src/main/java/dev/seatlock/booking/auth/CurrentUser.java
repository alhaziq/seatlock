package dev.seatlock.booking.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import io.swagger.v3.oas.annotations.Parameter;

/** Resolves a controller parameter to the caller's user id: the authenticated JWT's {@code sub} as a UUID. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@AuthenticationPrincipal(expression = "T(java.util.UUID).fromString(subject)")
@Parameter(hidden = true)
public @interface CurrentUser {
}
