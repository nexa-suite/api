package com.nexa.api.tenantaccessgovernance.support.application.publicapi;

/** Public application outcome mapped by the HTTP edge for support-console commands. */
@org.springframework.modulith.NamedInterface(value = "support-console-errors", propagate = false)
public final class SupportConsoleOperationException extends RuntimeException {
	private enum Kind { FORBIDDEN, CONFLICT }

	private final Kind kind;

	private SupportConsoleOperationException(Kind kind, String message) {
		super(message);
		this.kind = java.util.Objects.requireNonNull(kind);
	}

	public static SupportConsoleOperationException forbidden(String message) {
		return new SupportConsoleOperationException(Kind.FORBIDDEN, message);
	}

	public static SupportConsoleOperationException conflict(String message) {
		return new SupportConsoleOperationException(Kind.CONFLICT, message);
	}

	public boolean isForbidden() { return kind == Kind.FORBIDDEN; }
}
