package com.nexa.api.shared.presentation.error;

import com.nexa.api.iam.application.exception.InvalidCredentialsException;
import com.nexa.api.iam.application.exception.InvalidRefreshTokenException;
import com.nexa.api.iam.application.exception.SessionNotFoundException;
import com.nexa.api.iam.application.exception.AuthenticationThrottledException;
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
import com.nexa.api.tenantmanagement.domain.model.administration.OrganizationAdministrationInvariantViolation;
import com.nexa.api.tenantmanagement.application.service.OrganizationAdministrationService.ConcurrencyConflictException;
import com.nexa.api.tenantmanagement.presentation.rest.OrganizationAdministrationController.PreconditionRequiredException;
import com.nexa.api.sales.application.exception.IdempotencyKeyRequiredException;
import com.nexa.api.sales.application.exception.PurchaseRequestTransitionException;
import com.nexa.api.sales.application.exception.SalesConcurrencyConflictException;
import com.nexa.api.sales.application.exception.SalesPreconditionRequiredException;
import com.nexa.api.sales.application.exception.SalesResourceNotFoundException;
import com.nexa.api.sales.domain.exception.SalesInvariantViolation;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderInvariantViolation;
import com.nexa.api.sales.application.exception.SalesOrderRejectionReasonRequiredException;
import com.nexa.api.sales.application.exception.SalesOrderTransitionException;
import com.nexa.api.shared.application.changefeed.ChangeFeedCapacityException;
import com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public final class GlobalExceptionHandler {
	private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(InvalidCredentialsException.class)
	public ResponseEntity<ProblemDetail> handleInvalidCredentials(InvalidCredentialsException exception, HttpServletRequest request) {
		return response(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTHENTICATION_FAILED, "Authentication failed", request);
	}

	@ExceptionHandler(AuthenticationThrottledException.class)
	public ResponseEntity<ProblemDetail> handleAuthenticationThrottled(AuthenticationThrottledException exception,
			HttpServletRequest request) {
		return response(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCode.AUTHENTICATION_THROTTLED,
				"Authentication temporarily unavailable", request);
	}

	@ExceptionHandler({InvalidRefreshTokenException.class, SessionNotFoundException.class})
	public ResponseEntity<ProblemDetail> handleInvalidSession(RuntimeException exception, HttpServletRequest request) {
		return response(HttpStatus.UNAUTHORIZED, ApiErrorCode.REFRESH_SESSION_INVALID, "Authentication session is invalid", request);
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
		return response(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN, "Access to this resource is denied", request);
	}

	@ExceptionHandler(PreconditionRequiredException.class)
	public ResponseEntity<ProblemDetail> handlePrecondition(PreconditionRequiredException exception, HttpServletRequest request) {
		return response(HttpStatus.PRECONDITION_REQUIRED, ApiErrorCode.PRECONDITION_REQUIRED, "If-Match header is required", request);
	}

	@ExceptionHandler(ConcurrencyConflictException.class)
	public ResponseEntity<ProblemDetail> handleConcurrency(ConcurrencyConflictException exception, HttpServletRequest request) {
		return response(HttpStatus.CONFLICT, ApiErrorCode.CONCURRENCY_CONFLICT, "Resource changed by another request", request);
	}

	@ExceptionHandler(OrganizationAdministrationInvariantViolation.class)
	public ResponseEntity<ProblemDetail> handleOrganizationInvariant(OrganizationAdministrationInvariantViolation exception, HttpServletRequest request) {
		ApiErrorCode code = exception.getMessage() != null && exception.getMessage().contains("Cross-surface")
				? ApiErrorCode.ROLE_TRANSITION_NOT_ALLOWED : ApiErrorCode.LAST_ACTIVE_OWNER_REQUIRED;
		return response(HttpStatus.CONFLICT, code, "Organization membership policy prevents this change", request);
	}
	@ExceptionHandler(AccessPolicyViolation.class)
	public ResponseEntity<ProblemDetail> handleAccessPolicy(AccessPolicyViolation exception, HttpServletRequest request) {
		return response(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN, "Access to this resource is denied", request);
	}

	@ExceptionHandler(SalesResourceNotFoundException.class)
	public ResponseEntity<ProblemDetail> handleSalesNotFound(SalesResourceNotFoundException exception, HttpServletRequest request) {
		ApiErrorCode code = switch (exception.getMessage()) {
			case "purchase-request" -> ApiErrorCode.PURCHASE_REQUEST_NOT_FOUND;
			case "sales-order" -> ApiErrorCode.SALES_ORDER_NOT_FOUND;
			case "catalog-item" -> ApiErrorCode.CATALOG_ITEM_NOT_FOUND;
			default -> ApiErrorCode.CLIENT_ACCOUNT_NOT_FOUND;
		};
		return response(HttpStatus.NOT_FOUND, code, "Resource not found", request);
	}
	@ExceptionHandler(SalesInvariantViolation.class)
	public ResponseEntity<ProblemDetail> handleSalesInvariant(SalesInvariantViolation exception, HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, ApiErrorCode.PURCHASE_REQUEST_LINE_INVALID, "Sales request is invalid", request);
	}
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ProblemDetail> handleSalesConstraint(DataIntegrityViolationException exception, HttpServletRequest request) {
		LOGGER.warn("Data integrity constraint rejected request {}", request.getRequestURI(), exception.getMostSpecificCause());
		String message = exception.getMostSpecificCause() == null ? "" : String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase(java.util.Locale.ROOT);
		ApiErrorCode code = message.contains("code") ? ApiErrorCode.CLIENT_ACCOUNT_CODE_CONFLICT
				: message.contains("tax") ? ApiErrorCode.CLIENT_ACCOUNT_TAX_ID_CONFLICT
				: message.contains("membership") ? ApiErrorCode.BUYER_MEMBERSHIP_ALREADY_ASSIGNED : ApiErrorCode.INVALID_REQUEST;
		return response(HttpStatus.CONFLICT, code, "Sales resource conflicts with existing data", request);
	}

	@ExceptionHandler(SalesConcurrencyConflictException.class)
	public ResponseEntity<ProblemDetail> handleSalesConcurrency(SalesConcurrencyConflictException exception, HttpServletRequest request) { return response(HttpStatus.CONFLICT, ApiErrorCode.CONCURRENCY_CONFLICT, "Resource changed by another request", request); }
	@ExceptionHandler(SalesPreconditionRequiredException.class)
	public ResponseEntity<ProblemDetail> handleSalesPrecondition(SalesPreconditionRequiredException exception, HttpServletRequest request) { return response(HttpStatus.PRECONDITION_REQUIRED, ApiErrorCode.PRECONDITION_REQUIRED, "If-Match header is required", request); }
	@ExceptionHandler(IdempotencyKeyRequiredException.class)
	public ResponseEntity<ProblemDetail> handleIdempotency(IdempotencyKeyRequiredException exception, HttpServletRequest request) { return response(HttpStatus.BAD_REQUEST, ApiErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key header is required", request); }
		@ExceptionHandler(PurchaseRequestTransitionException.class)
		public ResponseEntity<ProblemDetail> handleTransition(PurchaseRequestTransitionException exception, HttpServletRequest request) { return response(HttpStatus.CONFLICT, ApiErrorCode.PURCHASE_REQUEST_TRANSITION_INVALID, "Purchase request transition is not allowed", request); }
		@ExceptionHandler(SalesOrderTransitionException.class)
		public ResponseEntity<ProblemDetail> handleSalesOrderTransition(SalesOrderTransitionException exception, HttpServletRequest request) { return response(HttpStatus.CONFLICT, ApiErrorCode.SALES_ORDER_TRANSITION_INVALID, "Sales order transition is not allowed", request); }
		@ExceptionHandler(SalesOrderRejectionReasonRequiredException.class)
		public ResponseEntity<ProblemDetail> handleSalesOrderRejectionReason(SalesOrderRejectionReasonRequiredException exception, HttpServletRequest request) { return response(HttpStatus.BAD_REQUEST, ApiErrorCode.SALES_ORDER_REJECTION_REASON_REQUIRED, "Sales order rejection reason is required", request); }
		@ExceptionHandler(SalesOrderInvariantViolation.class)
		public ResponseEntity<ProblemDetail> handleSalesOrderInvariant(SalesOrderInvariantViolation exception, HttpServletRequest request) { return response(HttpStatus.BAD_REQUEST, ApiErrorCode.SALES_ORDER_INVALID, "Sales order is invalid", request); }
		@ExceptionHandler(ChangeFeedCapacityException.class)
		public ResponseEntity<ProblemDetail> handleChangeFeedCapacity(ChangeFeedCapacityException exception, HttpServletRequest request) { return response(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCode.CHANGE_FEED_CONNECTION_LIMIT, "Change feed connection limit reached", request); }

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
		LOGGER.error("Unexpected API exception correlationId={}", ApiProblemDetailFactory.correlationId(request), exception);
		return response(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR, "Internal server error", request);
	}

	private static ProblemDetail problem(HttpStatus status, ApiErrorCode code, String detail, HttpServletRequest request) {
		return ApiProblemDetailFactory.create(status, code, detail, request);
	}

	private static ResponseEntity<ProblemDetail> response(HttpStatus status, ApiErrorCode code, String detail, HttpServletRequest request) {
		return ResponseEntity.status(status).body(problem(status, code, detail, request));
	}
}
