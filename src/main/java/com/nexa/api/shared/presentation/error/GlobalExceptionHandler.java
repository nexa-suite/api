package com.nexa.api.shared.presentation.error;

import com.nexa.api.iam.application.exception.InvalidCredentialsException;
import com.nexa.api.iam.application.exception.InvalidRefreshTokenException;
import com.nexa.api.iam.application.exception.SessionNotFoundException;
import com.nexa.api.iam.application.exception.AuthenticationThrottledException;
import com.nexa.api.iam.application.exception.IamSecurityException;
import com.nexa.api.shared.presentation.http.CorrelationIdFilter;
import com.nexa.api.shared.application.error.TechnicalFailureException;
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
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.validation.BindException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.security.access.AccessDeniedException;
import com.nexa.api.tenantmanagement.domain.model.administration.OrganizationAdministrationInvariantViolation;
import com.nexa.api.tenantmanagement.application.service.OrganizationAdministrationService.ConcurrencyConflictException;
import com.nexa.api.tenantmanagement.application.service.OrganizationInvitationService.InvitationConflictException;
import com.nexa.api.tenantmanagement.application.service.OrganizationInvitationService.InvitationIdempotencyConflictException;
import com.nexa.api.tenantmanagement.application.service.OrganizationInvitationService.InvitationIdempotencyRequiredException;
import com.nexa.api.tenantmanagement.application.service.OrganizationInvitationService.InvitationInvalidException;
import com.nexa.api.tenantmanagement.application.service.TenantConfigurationService.CustomFieldConflictException;
import com.nexa.api.tenantmanagement.presentation.rest.OrganizationAdministrationController.PreconditionRequiredException;
import com.nexa.api.sales.application.exception.IdempotencyKeyRequiredException;
import com.nexa.api.sales.application.exception.PurchaseRequestTransitionException;
import com.nexa.api.sales.application.exception.PurchaseRequestAlreadyConvertedException;
import com.nexa.api.sales.application.exception.PurchaseRequestDraftConcurrencyException;
import com.nexa.api.sales.application.exception.PurchaseRequestDraftInvariantException;
import com.nexa.api.sales.application.exception.PurchaseRequestDraftPreconditionRequiredException;
import com.nexa.api.sales.application.exception.SalesConcurrencyConflictException;
import com.nexa.api.sales.application.exception.SalesIdempotencyPayloadConflictException;
import com.nexa.api.sales.application.exception.SalesPreconditionRequiredException;
import com.nexa.api.sales.application.exception.SalesResourceNotFoundException;
import com.nexa.api.sales.domain.exception.SalesInvariantViolation;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderInvariantViolation;
import com.nexa.api.sales.application.exception.SalesOrderRejectionReasonRequiredException;
import com.nexa.api.sales.application.exception.SalesOrderTransitionException;
import com.nexa.api.shared.application.changefeed.ChangeFeedCapacityException;
import com.nexa.api.warehouse.application.WarehouseOperationsService;
import com.nexa.api.logistics.application.LogisticsOperationsService;
import com.nexa.api.logistics.domain.dispatchorder.DispatchTransitionViolation;
import com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import com.nexa.api.catalogmanagement.application.exception.CatalogConcurrencyException;
import com.nexa.api.catalogmanagement.application.exception.CatalogConflictException;
import com.nexa.api.catalogmanagement.application.exception.CatalogIdempotencyKeyRequiredException;
import com.nexa.api.catalogmanagement.application.exception.CatalogPreconditionRequiredException;
import com.nexa.api.catalogmanagement.application.exception.CatalogResourceNotFoundException;
import com.nexa.api.tenantmanagement.application.service.RoleDefinitionService;
import com.nexa.api.tenantmanagement.application.exception.RoleDefinitionPersistenceUnavailableException;

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

	@ExceptionHandler(IamSecurityException.class)
	public ResponseEntity<ProblemDetail> handleIamSecurity(IamSecurityException exception, HttpServletRequest request) {
		ApiErrorCode code = switch (exception.code()) {
			case "RESET_RATE_LIMITED" -> ApiErrorCode.RESET_RATE_LIMITED;
			case "PUBLIC_CONTACT_RATE_LIMITED" -> ApiErrorCode.PUBLIC_CONTACT_RATE_LIMITED;
			case "PROFILE_VERSION_CONFLICT" -> ApiErrorCode.PROFILE_VERSION_CONFLICT;
			case "PROFILE_PRECONDITION_REQUIRED" -> ApiErrorCode.PRECONDITION_REQUIRED;
			case "PASSWORD_POLICY_INVALID" -> ApiErrorCode.PASSWORD_POLICY_INVALID;
			case "PASSWORD_REUSE_NOT_ALLOWED" -> ApiErrorCode.PASSWORD_REUSE_NOT_ALLOWED;
			case "PASSWORD_CHANGE_FAILED" -> ApiErrorCode.PASSWORD_CHANGE_FAILED;
			case "RESET_INVALID" -> ApiErrorCode.RESET_INVALID;
			case "REGISTRATION_SLUG_CONFLICT" -> ApiErrorCode.REGISTRATION_SLUG_CONFLICT;
			case "FOUNDER_EMAIL_INCOMPATIBLE" -> ApiErrorCode.FOUNDER_EMAIL_INCOMPATIBLE;
			case "REGISTRATION_NOT_PENDING" -> ApiErrorCode.REGISTRATION_NOT_PENDING;
			case "SYSTEM_OPERATOR_REQUIRED" -> ApiErrorCode.SYSTEM_OPERATOR_REQUIRED;
			case "REJECTION_REASON_REQUIRED" -> ApiErrorCode.REJECTION_REASON_REQUIRED;
			case "PROFILE_INVALID" -> ApiErrorCode.PROFILE_INVALID;
			default -> ApiErrorCode.REGISTRATION_INVALID;
		};
		HttpStatus status = switch (code) {
			case RESET_RATE_LIMITED, PUBLIC_CONTACT_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
			case PROFILE_VERSION_CONFLICT, REGISTRATION_SLUG_CONFLICT, FOUNDER_EMAIL_INCOMPATIBLE, REGISTRATION_NOT_PENDING -> HttpStatus.CONFLICT;
			case PRECONDITION_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
			case SYSTEM_OPERATOR_REQUIRED -> HttpStatus.FORBIDDEN;
			default -> HttpStatus.BAD_REQUEST;
		};
		String detail = code == ApiErrorCode.RESET_RATE_LIMITED ? "Password reset requests are temporarily limited"
				: code == ApiErrorCode.PUBLIC_CONTACT_RATE_LIMITED ? "Contact requests are temporarily limited"
				: code == ApiErrorCode.RESET_INVALID ? "The reset request is invalid or expired" : "The requested security operation could not be completed";
		return response(status, code, detail, request);
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

	@ExceptionHandler(CatalogConcurrencyException.class)
	public ResponseEntity<ProblemDetail> handleCatalogConcurrency(CatalogConcurrencyException exception, HttpServletRequest request) {
		return response(HttpStatus.CONFLICT, ApiErrorCode.CONCURRENCY_CONFLICT, "Catalog resource changed by another request", request);
	}

	@ExceptionHandler(CatalogPreconditionRequiredException.class)
	public ResponseEntity<ProblemDetail> handleCatalogPrecondition(CatalogPreconditionRequiredException exception, HttpServletRequest request) {
		return response(HttpStatus.PRECONDITION_REQUIRED, ApiErrorCode.PRECONDITION_REQUIRED, "If-Match header is required", request);
	}

	@ExceptionHandler(CatalogResourceNotFoundException.class)
	public ResponseEntity<ProblemDetail> handleCatalogNotFound(CatalogResourceNotFoundException exception, HttpServletRequest request) {
		ApiErrorCode code = switch (exception.resource()) {
			case "category" -> ApiErrorCode.CATALOG_CATEGORY_NOT_FOUND;
			case "brand" -> ApiErrorCode.CATALOG_BRAND_NOT_FOUND;
			case "product" -> ApiErrorCode.CATALOG_PRODUCT_NOT_FOUND;
			case "price" -> ApiErrorCode.CATALOG_PRICE_NOT_FOUND;
			case "promotion" -> ApiErrorCode.CATALOG_PROMOTION_NOT_FOUND;
			default -> ApiErrorCode.RESOURCE_NOT_FOUND;
		};
		return response(HttpStatus.NOT_FOUND, code, "Catalog resource not found", request);
	}

	@ExceptionHandler(CatalogConflictException.class)
	public ResponseEntity<ProblemDetail> handleCatalogConflict(CatalogConflictException exception, HttpServletRequest request) {
		ApiErrorCode code = switch (exception.code()) {
			case "CATALOG_CATEGORY_CYCLE" -> ApiErrorCode.CATALOG_CATEGORY_CYCLE;
			case "CATALOG_PRICE_OVERLAP" -> ApiErrorCode.CATALOG_PRICE_OVERLAP;
			case "CATALOG_CURRENCY_MISMATCH" -> ApiErrorCode.CATALOG_CURRENCY_MISMATCH;
			case "PROMOTION_LIFECYCLE_INVALID" -> ApiErrorCode.PROMOTION_LIFECYCLE_INVALID;
			default -> ApiErrorCode.CATALOG_CONFLICT;
		};
		return response(HttpStatus.CONFLICT, code, "Catalog operation conflicts with current state", request);
	}

	@ExceptionHandler(CatalogIdempotencyKeyRequiredException.class)
	public ResponseEntity<ProblemDetail> handleCatalogIdempotency(CatalogIdempotencyKeyRequiredException exception, HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, ApiErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key header is required", request);
	}

	@ExceptionHandler(InvitationInvalidException.class)
	public ResponseEntity<ProblemDetail> handleInvitationInvalid(InvitationInvalidException exception, HttpServletRequest request) {
		return response(HttpStatus.NOT_FOUND, ApiErrorCode.INVITATION_INVALID, "Invitation is invalid or expired", request);
	}

	@ExceptionHandler(InvitationConflictException.class)
	public ResponseEntity<ProblemDetail> handleInvitationConflict(InvitationConflictException exception, HttpServletRequest request) {
		return response(HttpStatus.CONFLICT, ApiErrorCode.INVITATION_CONFLICT, "Invitation cannot be completed", request);
	}

	@ExceptionHandler(InvitationIdempotencyRequiredException.class)
	public ResponseEntity<ProblemDetail> handleInvitationIdempotencyRequired(InvitationIdempotencyRequiredException exception, HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, ApiErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key header is required", request);
	}

	@ExceptionHandler(InvitationIdempotencyConflictException.class)
	public ResponseEntity<ProblemDetail> handleInvitationIdempotencyConflict(InvitationIdempotencyConflictException exception, HttpServletRequest request) {
		return response(HttpStatus.CONFLICT, ApiErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT, "Idempotency key was reused with a different invitation", request);
	}

	@ExceptionHandler(CustomFieldConflictException.class)
	public ResponseEntity<ProblemDetail> handleCustomFieldConflict(CustomFieldConflictException exception, HttpServletRequest request) {
		return response(HttpStatus.CONFLICT, ApiErrorCode.CUSTOM_FIELD_CONFLICT, "Custom field key already exists", request);
	}

	@ExceptionHandler(OrganizationAdministrationInvariantViolation.class)
	public ResponseEntity<ProblemDetail> handleOrganizationInvariant(OrganizationAdministrationInvariantViolation exception, HttpServletRequest request) {
		String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(java.util.Locale.ROOT);
		ApiErrorCode code = message.contains("cross-surface")
				? ApiErrorCode.ROLE_TRANSITION_NOT_ALLOWED
				: message.contains("usable administrative workspace")
				? ApiErrorCode.LAST_USABLE_ADMINISTRATIVE_WORKSPACE_REQUIRED : ApiErrorCode.LAST_ACTIVE_OWNER_REQUIRED;
		return response(HttpStatus.CONFLICT, code, "Organization membership policy prevents this change", request);
	}

	@ExceptionHandler(com.nexa.api.tenantmanagement.application.service.OrganizationAdministrationService.IdempotencyKeyRequiredException.class)
	public ResponseEntity<ProblemDetail> handleOrganizationIdempotency(com.nexa.api.tenantmanagement.application.service.OrganizationAdministrationService.IdempotencyKeyRequiredException exception, HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, ApiErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key header is required", request);
	}
	@ExceptionHandler(com.nexa.api.tenantmanagement.application.service.OrganizationAdministrationService.IdempotencyPayloadConflictException.class)
	public ResponseEntity<ProblemDetail> handleOrganizationIdempotencyPayload(com.nexa.api.tenantmanagement.application.service.OrganizationAdministrationService.IdempotencyPayloadConflictException exception, HttpServletRequest request) {
		return response(HttpStatus.CONFLICT, ApiErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT, "Idempotency key was reused with a different workspace", request);
	}
	@ExceptionHandler(AccessPolicyViolation.class)
	public ResponseEntity<ProblemDetail> handleAccessPolicy(AccessPolicyViolation exception, HttpServletRequest request) {
		return response(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN, "Access to this resource is denied", request);
	}

	@ExceptionHandler(RoleDefinitionService.RoleDefinitionNotFoundException.class)
	public ResponseEntity<ProblemDetail> handleRoleDefinitionNotFound(RoleDefinitionService.RoleDefinitionNotFoundException exception, HttpServletRequest request) {
		return response(HttpStatus.NOT_FOUND, ApiErrorCode.ROLE_DEFINITION_NOT_FOUND, "Role definition not found", request);
	}

	@ExceptionHandler(RoleDefinitionService.DuplicateRoleDefinitionException.class)
	public ResponseEntity<ProblemDetail> handleRoleDefinitionDuplicate(RoleDefinitionService.DuplicateRoleDefinitionException exception, HttpServletRequest request) {
		return response(HttpStatus.CONFLICT, ApiErrorCode.ROLE_DEFINITION_DUPLICATE, "Role definition code already exists", request);
	}

	@ExceptionHandler(RoleDefinitionService.ImmutableRoleDefinitionException.class)
	public ResponseEntity<ProblemDetail> handleRoleDefinitionImmutable(RoleDefinitionService.ImmutableRoleDefinitionException exception, HttpServletRequest request) {
		return response(HttpStatus.CONFLICT, ApiErrorCode.ROLE_DEFINITION_IMMUTABLE, "System role definitions are immutable", request);
	}

	@ExceptionHandler(RoleDefinitionService.ActiveRoleDefinitionAssignmentsException.class)
	public ResponseEntity<ProblemDetail> handleRoleDefinitionAssignments(RoleDefinitionService.ActiveRoleDefinitionAssignmentsException exception, HttpServletRequest request) {
		return response(HttpStatus.CONFLICT, ApiErrorCode.ROLE_DEFINITION_ASSIGNMENTS_ACTIVE, "Active memberships still use this role", request);
	}

	@ExceptionHandler(RoleDefinitionService.RoleDefinitionConcurrencyException.class)
	public ResponseEntity<ProblemDetail> handleRoleDefinitionConcurrency(RoleDefinitionService.RoleDefinitionConcurrencyException exception, HttpServletRequest request) {
		return response(HttpStatus.CONFLICT, ApiErrorCode.CONCURRENCY_CONFLICT, "Role definition changed by another request", request);
	}

	@ExceptionHandler(RoleDefinitionPersistenceUnavailableException.class)
	public ResponseEntity<ProblemDetail> handleRoleDefinitionPersistenceUnavailable(RoleDefinitionPersistenceUnavailableException exception, HttpServletRequest request) {
		return response(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.ROLE_DEFINITION_STORAGE_UNAVAILABLE, "Role definition persistence is unavailable", request);
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
		LOGGER.warn("Sales invariant rejected request {}: {}", request.getRequestURI(), exception.getMessage());
		return response(HttpStatus.BAD_REQUEST, ApiErrorCode.PURCHASE_REQUEST_LINE_INVALID, "Sales request is invalid", request);
	}
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ProblemDetail> handleSalesConstraint(DataIntegrityViolationException exception, HttpServletRequest request) {
		LOGGER.warn("Data integrity constraint rejected request {}", request.getRequestURI(), exception.getMostSpecificCause());
		String message = exception.getMostSpecificCause() == null ? "" : String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase(java.util.Locale.ROOT);
		ApiErrorCode code = message.contains("ex_catalog_price_no_overlap") || message.contains("ex_sku_price_no_overlap") ? ApiErrorCode.CATALOG_PRICE_OVERLAP
				: message.contains("uq_catalog_category_slug") || message.contains("uq_catalog_brand_slug") || message.contains("uq_catalog_product_") || message.contains("uq_catalog_promotion_slug") ? ApiErrorCode.CATALOG_CONFLICT
				: message.contains("uq_organization_registration_slug") ? ApiErrorCode.REGISTRATION_SLUG_CONFLICT
				: message.contains("uq_workspace_tenant_slug") ? ApiErrorCode.WORKSPACE_SLUG_CONFLICT
				: message.contains("uq_organization_invitation_active_email") ? ApiErrorCode.INVITATION_CONFLICT
				: message.contains("uq_custom_field_definition_key") ? ApiErrorCode.CUSTOM_FIELD_CONFLICT
				: message.contains("code") ? ApiErrorCode.CLIENT_ACCOUNT_CODE_CONFLICT
				: message.contains("tax") ? ApiErrorCode.CLIENT_ACCOUNT_TAX_ID_CONFLICT
				: message.contains("membership") ? ApiErrorCode.BUYER_MEMBERSHIP_ALREADY_ASSIGNED : ApiErrorCode.INVALID_REQUEST;
		String detail = code == ApiErrorCode.REGISTRATION_SLUG_CONFLICT
				? "Organization workspace slug is already registered" : "Sales resource conflicts with existing data";
		return response(HttpStatus.CONFLICT, code, detail, request);
	}
	@ExceptionHandler(InvalidDataAccessApiUsageException.class)
	public ResponseEntity<ProblemDetail> handleInvalidDataAccessUsage(InvalidDataAccessApiUsageException exception, HttpServletRequest request) {
		String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(java.util.Locale.ROOT);
		if (hasCauseMessage(exception, "Business document not found")) {
			return response(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Resource not found", request);
		}
		if (message.contains("evidence rejected") || message.contains("mime type mismatch") || message.contains("empty_file")
				|| message.contains("malware") || message.contains("unknown_content_type")) {
			return response(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_REQUEST, "Evidence is invalid", request);
		}
		return handleUnexpected(exception, request);
	}

	@ExceptionHandler(SalesConcurrencyConflictException.class)
	public ResponseEntity<ProblemDetail> handleSalesConcurrency(SalesConcurrencyConflictException exception, HttpServletRequest request) { return response(HttpStatus.CONFLICT, ApiErrorCode.CONCURRENCY_CONFLICT, "Resource changed by another request", request); }
	@ExceptionHandler(PurchaseRequestDraftConcurrencyException.class)
	public ResponseEntity<ProblemDetail> handlePurchaseRequestDraftConcurrency(PurchaseRequestDraftConcurrencyException exception, HttpServletRequest request) { return response(HttpStatus.PRECONDITION_FAILED, ApiErrorCode.CONCURRENCY_CONFLICT, "Purchase request draft version is stale", request); }
	@ExceptionHandler(PurchaseRequestDraftInvariantException.class)
	public ResponseEntity<ProblemDetail> handlePurchaseRequestDraftInvariant(PurchaseRequestDraftInvariantException exception, HttpServletRequest request) { return response(HttpStatus.CONFLICT, ApiErrorCode.INVALID_TRANSITION, "Purchase request draft is not ready to submit", request); }
	@ExceptionHandler(SalesIdempotencyPayloadConflictException.class)
	public ResponseEntity<ProblemDetail> handleSalesIdempotencyPayload(SalesIdempotencyPayloadConflictException exception, HttpServletRequest request) { return response(HttpStatus.CONFLICT, ApiErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT, "Idempotency key was reused with a different payload", request); }
	@ExceptionHandler(PurchaseRequestAlreadyConvertedException.class)
	public ResponseEntity<ProblemDetail> handlePurchaseRequestAlreadyConverted(PurchaseRequestAlreadyConvertedException exception, HttpServletRequest request) { return response(HttpStatus.CONFLICT, ApiErrorCode.PURCHASE_REQUEST_ALREADY_CONVERTED, "Purchase request has already been converted", request); }
	@ExceptionHandler(SalesPreconditionRequiredException.class)
	public ResponseEntity<ProblemDetail> handleSalesPrecondition(SalesPreconditionRequiredException exception, HttpServletRequest request) { return response(HttpStatus.PRECONDITION_REQUIRED, ApiErrorCode.PRECONDITION_REQUIRED, "If-Match header is required", request); }
	@ExceptionHandler(PurchaseRequestDraftPreconditionRequiredException.class)
	public ResponseEntity<ProblemDetail> handlePurchaseRequestDraftPrecondition(PurchaseRequestDraftPreconditionRequiredException exception, HttpServletRequest request) { return response(HttpStatus.PRECONDITION_REQUIRED, ApiErrorCode.PRECONDITION_REQUIRED, "If-Match header is required", request); }
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
		@ExceptionHandler(WarehouseOperationsService.WarehouseException.class)
		public ResponseEntity<ProblemDetail> handleWarehouse(WarehouseOperationsService.WarehouseException exception, HttpServletRequest request) {
			ApiErrorCode code; try { code = ApiErrorCode.valueOf(exception.code()); } catch (IllegalArgumentException ignored) { code = ApiErrorCode.INVALID_REQUEST; }
				HttpStatus status = exception.notFound() ? HttpStatus.NOT_FOUND : switch (exception.code()) { case "CONCURRENCY_CONFLICT", "INVENTORY_SHORTAGE", "IDEMPOTENCY_PAYLOAD_CONFLICT", "INVENTORY_RESERVATION_ALREADY_EXISTS" -> HttpStatus.CONFLICT; case "FORBIDDEN" -> HttpStatus.FORBIDDEN; case "PRECONDITION_REQUIRED" -> HttpStatus.PRECONDITION_REQUIRED; default -> HttpStatus.BAD_REQUEST; };
			return response(status, code, "Warehouse operation could not be completed", request);
		}
		@ExceptionHandler(LogisticsOperationsService.LogisticsException.class)
		public ResponseEntity<ProblemDetail> handleLogistics(LogisticsOperationsService.LogisticsException exception, HttpServletRequest request) {
			ApiErrorCode code; try { code = ApiErrorCode.valueOf(exception.code()); } catch (IllegalArgumentException ignored) { code = ApiErrorCode.INVALID_REQUEST; }
			HttpStatus status = exception.notFound() ? HttpStatus.NOT_FOUND : switch (exception.code()) {
				case "CONCURRENCY_CONFLICT", "INVENTORY_SHORTAGE", "IDEMPOTENCY_PAYLOAD_CONFLICT", "DISPATCH_ALREADY_EXISTS", "INVALID_TRANSITION", "RESERVATION_NOT_READY" -> HttpStatus.CONFLICT;
				case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
				case "PRECONDITION_REQUIRED" -> HttpStatus.PRECONDITION_REQUIRED;
				default -> HttpStatus.BAD_REQUEST;
			};
			return response(status, code, "Logistics operation could not be completed", request);
		}
		@ExceptionHandler(DispatchTransitionViolation.class)
		public ResponseEntity<ProblemDetail> handleDispatchTransition(DispatchTransitionViolation exception, HttpServletRequest request) {
			return response(HttpStatus.CONFLICT, ApiErrorCode.INVALID_TRANSITION, "Logistics transition is not allowed", request);
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

	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException exception, HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_ERROR, "Request validation failed", request);
	}

	@ExceptionHandler(BindException.class)
	public ResponseEntity<ProblemDetail> handleBinding(BindException exception, HttpServletRequest request) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_ERROR, "Request validation failed", request);
		ApiProblemDetailFactory.addValidationErrors(problem, exception.getBindingResult().getFieldErrors().stream()
				.map(error -> Map.of("field", error.getField(), "message", "Invalid value"))
				.toList());
		return ResponseEntity.badRequest().body(problem);
	}

	@ExceptionHandler(com.nexa.api.shared.application.error.ApiResourceNotFoundException.class)
	public ResponseEntity<ProblemDetail> handleApiNotFound(com.nexa.api.shared.application.error.ApiResourceNotFoundException exception, HttpServletRequest request) {
		return response(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Resource not found", request);
	}

	@ExceptionHandler(com.nexa.api.payments.application.exception.PaymentOperationInProgressException.class)
	public ResponseEntity<ProblemDetail> handlePaymentOperationInProgress(com.nexa.api.payments.application.exception.PaymentOperationInProgressException exception, HttpServletRequest request) {
		return response(HttpStatus.CONFLICT, ApiErrorCode.CONCURRENCY_CONFLICT, "A payment operation is already in progress for this receivable", request);
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ProblemDetail> handleDomainValidation(RuntimeException exception, HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_REQUEST, "Request parameters are invalid", request);
	}

	@ExceptionHandler(TechnicalFailureException.class)
	public ResponseEntity<ProblemDetail> handleTechnicalFailure(TechnicalFailureException exception, HttpServletRequest request) {
		ApiErrorCode code = switch (exception.kind()) {
			case EXTERNAL_TEMPORARY_FAILURE -> ApiErrorCode.EXTERNAL_TEMPORARY_FAILURE;
			case EXTERNAL_TIMEOUT -> ApiErrorCode.EXTERNAL_TIMEOUT;
			case TECHNICAL_CAPABILITY_UNAVAILABLE -> ApiErrorCode.TECHNICAL_CAPABILITY_UNAVAILABLE;
			case STORAGE_UNAVAILABLE -> ApiErrorCode.STORAGE_UNAVAILABLE;
			case SCANNER_UNAVAILABLE -> ApiErrorCode.SCANNER_UNAVAILABLE;
		};
		HttpStatus status = switch (exception.kind()) {
			case TECHNICAL_CAPABILITY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
			case EXTERNAL_TIMEOUT, EXTERNAL_TEMPORARY_FAILURE, STORAGE_UNAVAILABLE, SCANNER_UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
		};
		LOGGER.warn("Technical failure code={} correlationId={} providerRequestId={}", code, ApiProblemDetailFactory.correlationId(request), exception.providerRequestId());
		return response(status, code, detail(code), request);
	}

	@ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class, MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
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
		if ("REGISTRATION_SLUG_CONFLICT".equals(exception.getMessage())) {
			return response(HttpStatus.CONFLICT, ApiErrorCode.REGISTRATION_SLUG_CONFLICT,
					"Organization workspace slug is already registered", request);
		}
		LOGGER.error("Unexpected API exception correlationId={}", ApiProblemDetailFactory.correlationId(request), exception);
		return response(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR, "Internal server error", request);
	}

	private static ProblemDetail problem(HttpStatus status, ApiErrorCode code, String detail, HttpServletRequest request) {
		return ApiProblemDetailFactory.create(status, code, detail, request);
	}

	private static ResponseEntity<ProblemDetail> response(HttpStatus status, ApiErrorCode code, String detail, HttpServletRequest request) {
		return ResponseEntity.status(status).body(problem(status, code, detail, request));
	}

	private static String detail(ApiErrorCode code) {
		return switch (code) {
			case EXTERNAL_TIMEOUT -> "An external service did not respond in time";
			case EXTERNAL_TEMPORARY_FAILURE -> "An external service is temporarily unavailable";
			case TECHNICAL_CAPABILITY_UNAVAILABLE -> "The requested technical capability is unavailable";
			case STORAGE_UNAVAILABLE -> "Private object storage is temporarily unavailable";
			case SCANNER_UNAVAILABLE -> "Malware scanning is temporarily unavailable";
			default -> "Technical operation could not be completed";
		};
	}

	private static boolean hasCauseMessage(Throwable exception, String expected) {
		for (Throwable current = exception; current != null; current = current.getCause()) {
			if (expected.equals(current.getMessage())) return true;
		}
		return false;
	}
}
