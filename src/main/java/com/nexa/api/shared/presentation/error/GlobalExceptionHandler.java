package com.nexa.api.shared.presentation.error;

import com.nexa.api.iam.application.exception.InvalidCredentialsException;
import com.nexa.api.iam.application.exception.InvalidRefreshTokenException;
import com.nexa.api.iam.application.exception.SessionNotFoundException;
import com.nexa.api.shared.presentation.http.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.validation.BindException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public final class GlobalExceptionHandler {
	private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(InvalidCredentialsException.class)
	public ResponseEntity<ProblemDetail> handleInvalidCredentials(InvalidCredentialsException exception, HttpServletRequest request) {
		return response(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTHENTICATION_FAILED, "Authentication failed", request);
	}

	@ExceptionHandler({InvalidRefreshTokenException.class, SessionNotFoundException.class})
	public ResponseEntity<ProblemDetail> handleInvalidSession(RuntimeException exception, HttpServletRequest request) {
		return response(HttpStatus.UNAUTHORIZED, ApiErrorCode.REFRESH_SESSION_INVALID, "Authentication session is invalid", request);
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
		return response(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN, "Access to this resource is denied", request);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_ERROR, "Request validation failed", request);
		ApiProblemDetailFactory.addValidationErrors(problem, exception.getBindingResult().getFieldErrors().stream()
				.map(error -> Map.of("field", error.getField(), "message", "Invalid value"))
				.toList());
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException exception, HttpServletRequest request) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_ERROR, "Request validation failed", request);
		ApiProblemDetailFactory.addValidationErrors(problem, exception.getConstraintViolations().stream()
				.map(error -> Map.of("field", error.getPropertyPath().toString(), "message", "Invalid value"))
				.toList());
		return ResponseEntity.badRequest().body(problem);
	}

	@ExceptionHandler(BindException.class)
	public ResponseEntity<ProblemDetail> handleBinding(BindException exception, HttpServletRequest request) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_ERROR, "Request validation failed", request);
		ApiProblemDetailFactory.addValidationErrors(problem, exception.getBindingResult().getFieldErrors().stream()
				.map(error -> Map.of("field", error.getField(), "message", "Invalid value"))
				.toList());
		return ResponseEntity.badRequest().body(problem);
	}

	@ExceptionHandler(ApiResourceNotFoundException.class)
	public ResponseEntity<ProblemDetail> handleApiNotFound(ApiResourceNotFoundException exception, HttpServletRequest request) {
		return response(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Resource not found", request);
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ProblemDetail> handleDomainValidation(RuntimeException exception, HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_REQUEST, "Request parameters are invalid", request);
	}

	@ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
	public ResponseEntity<ProblemDetail> handleInvalidRequest(Exception exception, HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_REQUEST, "Request body or parameters are invalid", request);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ProblemDetail> handleNotFound(NoResourceFoundException exception, HttpServletRequest request) {
		return response(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Resource not found", request);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ProblemDetail> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
		return response(HttpStatus.METHOD_NOT_ALLOWED, ApiErrorCode.METHOD_NOT_ALLOWED, "HTTP method is not supported", request);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
		LOGGER.error("Unexpected API exception correlationId={}", ApiProblemDetailFactory.correlationId(request));
		return response(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR, "Internal server error", request);
	}

	private static ProblemDetail problem(HttpStatus status, ApiErrorCode code, String detail, HttpServletRequest request) {
		return ApiProblemDetailFactory.create(status, code, detail, request);
	}

	private static ResponseEntity<ProblemDetail> response(HttpStatus status, ApiErrorCode code, String detail, HttpServletRequest request) {
		return ResponseEntity.status(status).body(problem(status, code, detail, request));
	}
}
