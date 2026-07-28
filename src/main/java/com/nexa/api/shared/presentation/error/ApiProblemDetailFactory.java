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
			case INTERNAL_ERROR -> "Internal server error";
		};
	}
}
