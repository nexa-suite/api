package com.nexa.api.shared.context;

/** Stable request metadata keys shared by boundary adapters without framework coupling. */
public final class RequestMetadata {
	public static final String CORRELATION_ID_ATTRIBUTE =
			"com.nexa.api.shared.presentation.http.CorrelationIdFilter.correlationId";
	public static final String TRACE_ID_ATTRIBUTE =
			"com.nexa.api.shared.presentation.http.TraceIdFilter.traceId";

	private RequestMetadata() { }
}
