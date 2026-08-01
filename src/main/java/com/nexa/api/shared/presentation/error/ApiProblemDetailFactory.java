package com.nexa.api.shared.presentation.error;

import com.nexa.api.shared.presentation.http.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

public final class ApiProblemDetailFactory {
	private ApiProblemDetailFactory() {
	}

	public static ProblemDetail create(HttpStatusCode status, ApiErrorCode code, String detail, HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create("urn:nexa:error:" + code.name().toLowerCase().replace('_', '-')));
		problem.setTitle(title(code));
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", code.name());
		problem.setProperty("correlationId", correlationId(request));
		return problem;
	}

	public static void addValidationErrors(ProblemDetail problem, List<Map<String, String>> errors) {
		problem.setProperty("errors", errors.stream().sorted(Comparator.comparing((Map<String, String> value) -> value.get("field")).thenComparing(value -> value.get("message"))).toList());
	}

	public static String correlationId(HttpServletRequest request) {
		Object attribute = request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME);
		return attribute instanceof String value && CorrelationIdFilter.isValid(value) ? value : "unknown";
	}

	private static String title(ApiErrorCode code) {
		return switch (code) {
			case VALIDATION_ERROR -> "Validation error";
			case INVALID_REQUEST -> "Invalid request";
			case RESOURCE_NOT_FOUND -> "Resource not found";
			case METHOD_NOT_ALLOWED -> "Method not allowed";
			case UNAUTHORIZED -> "Authentication required";
			case FORBIDDEN -> "Access denied";
			case AUTHENTICATION_REQUIRED -> "Authentication required";
			case AUTHENTICATION_FAILED -> "Authentication failed";
			case ACCESS_TOKEN_INVALID -> "Access token invalid";
			case AUTHENTICATION_THROTTLED -> "Authentication throttled";
			case REFRESH_SESSION_INVALID -> "Refresh session invalid";
			case ACCESS_CONTEXT_INVALID -> "Access context invalid";
			case WORKSPACE_ACCESS_DENIED -> "Workspace access denied";
			case SURFACE_ACCESS_DENIED -> "Surface access denied";
			case PERMISSION_DENIED -> "Permission denied";
			case ORIGIN_NOT_ALLOWED -> "Origin not allowed";
			case CATALOG_ITEM_NOT_FOUND -> "Catalog item not found";
			case INVALID_CATALOG_QUERY -> "Invalid catalog query";
			case ORGANIZATION_NOT_FOUND -> "Organization not found";
			case WORKSPACE_NOT_FOUND -> "Workspace not found";
			case MEMBERSHIP_NOT_FOUND -> "Membership not found";
			case LAST_ACTIVE_OWNER_REQUIRED -> "Last active owner required";
			case ROLE_TRANSITION_NOT_ALLOWED -> "Role transition not allowed";
			case CONCURRENCY_CONFLICT -> "Concurrency conflict";
			case INVALID_TRANSITION -> "Invalid transition";
			case PRECONDITION_REQUIRED -> "Precondition required";
			case CLIENT_ACCOUNT_NOT_FOUND -> "Client account not found";
			case CLIENT_ACCOUNT_CODE_CONFLICT -> "Client account code conflict";
			case CLIENT_ACCOUNT_TAX_ID_CONFLICT -> "Client account tax identifier conflict";
			case BUYER_MEMBERSHIP_ALREADY_ASSIGNED -> "Buyer membership already assigned";
			case PURCHASE_REQUEST_NOT_FOUND -> "Purchase request not found";
			case PURCHASE_REQUEST_TRANSITION_INVALID -> "Purchase request transition invalid";
			case PURCHASE_REQUEST_LINE_INVALID -> "Purchase request line invalid";
				case PURCHASE_REQUEST_CLIENT_SCOPE_INVALID -> "Purchase request client scope invalid";
				case IDEMPOTENCY_KEY_REQUIRED -> "Idempotency key required";
				case IDEMPOTENCY_PAYLOAD_CONFLICT -> "Idempotency payload conflict";
				case SALES_ORDER_NOT_FOUND -> "Sales order not found";
				case SALES_ORDER_TRANSITION_INVALID -> "Sales order transition invalid";
				case SALES_ORDER_REJECTION_REASON_REQUIRED -> "Sales order rejection reason required";
				case SALES_ORDER_INVALID -> "Sales order invalid";
				case CHANGE_FEED_CAPACITY -> "Change feed capacity unavailable";
				case CHANGE_FEED_CONNECTION_LIMIT -> "Change feed connection limit";
				case WAREHOUSE_NOT_FOUND -> "Warehouse not found";
				case STORAGE_ZONE_NOT_FOUND -> "Storage zone not found";
				case INVENTORY_LOT_NOT_FOUND -> "Inventory lot not found";
				case INVENTORY_LOT_NOT_ALLOCATABLE -> "Inventory lot is not allocatable";
				case INVENTORY_UNIT_MISMATCH -> "Inventory unit mismatch";
				case INSUFFICIENT_AVAILABLE_STOCK -> "Insufficient available stock";
				case INVENTORY_SHORTAGE -> "Inventory shortage";
				case INVENTORY_RESERVATION_NOT_FOUND -> "Inventory reservation not found";
				case INVENTORY_RESERVATION_ALREADY_EXISTS -> "Inventory reservation already exists";
				case INVENTORY_RESERVATION_TRANSITION_INVALID -> "Inventory reservation transition invalid";
				case INVENTORY_RESERVATION_EXPIRED -> "Inventory reservation expired";
				case FULFILLMENT_CANDIDATE_NOT_ELIGIBLE -> "Fulfillment candidate not eligible";
				case DISPATCH_READINESS_CANDIDATE_NOT_FOUND -> "Dispatch readiness candidate not found";
				case INVALID_INVENTORY_SORT -> "Invalid inventory sort";
				case INTERNAL_ERROR -> "Internal server error";
		};
	}
}
